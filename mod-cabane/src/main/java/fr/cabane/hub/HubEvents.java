package fr.cabane.hub;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.io.BufferedReader;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

public final class HubEvents {

    /** Les PNJ du centre de map : fichier data/cabane/pnj/&lt;nom&gt;.snbt, position autour du feu de camp. */
    private record Pnj(String file, double dx, double dz, float yaw) {
    }

    private static final List<Pnj> PNJ = List.of(
            new Pnj("gardien", 2.5, -3.5, 135F),
            new Pnj("cartographe", -4.5, -1.5, 180F),
            new Pnj("marchand", 7.5, 2.5, 90F));

    public static void onServerStarted(MinecraftServer server) {
        if (HubData.get(server).centre() != null) {
            return;
        }
        ServerLevel level = server.overworld();
        BlockPos centre = HubBuilder.trouverSavane(level);
        if (centre == null) {
            BlockPos spawn = level.getSharedSpawnPos();
            level.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
            centre = new BlockPos(spawn.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()) - 1, spawn.getZ());
            Cabane.LOGGER.warn("Aucune savane trouvée, la cabane est construite au spawn ({})", centre);
        }
        build(level, centre);
    }

    public static void build(ServerLevel level, BlockPos centre) {
        long start = System.currentTimeMillis();
        new HubBuilder(level, centre).construire();
        HubData.get(level.getServer()).setCentre(centre);
        level.setDefaultSpawnPos(centre.offset(0, 1, 5), 180F);
        level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer());
        // la cabane devient le vrai centre de la map : la bordure du monde se centre dessus
        level.getWorldBorder().setCenter(centre.getX() + 0.5, centre.getZ() + 0.5);
        int npcs = spawnNpcs(level);
        Cabane.LOGGER.info("Centre de map construit en {} : {} ms, {} PNJ", centre, System.currentTimeMillis() - start, npcs);
    }

    public static int spawnNpcs(ServerLevel level) {
        BlockPos centre = HubData.get(level.getServer()).centre();
        if (centre == null) {
            return 0;
        }
        int count = 0;
        for (Pnj pnj : PNJ) {
            if (spawn(level, centre, pnj)) {
                count++;
            }
        }
        return count;
    }

    private static boolean spawn(ServerLevel level, BlockPos centre, Pnj pnj) {
        ResourceLocation id = Cabane.id("pnj/" + pnj.file() + ".snbt");
        Optional<Resource> resource = level.getServer().getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            return false;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            CompoundTag tag = TagParser.parseTag(reader.lines().collect(Collectors.joining("\n")));
            if (tag.hasUUID("UUID")) {
                Entity old = level.getEntity(tag.getUUID("UUID"));
                if (old != null) {
                    old.discard();
                }
            }
            double x = centre.getX() + pnj.dx();
            double y = centre.getY() + 1;
            double z = centre.getZ() + pnj.dz();
            Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
                e.moveTo(x, y, z, pnj.yaw(), 0F);
                return e;
            });
            return entity != null && level.addFreshEntity(entity);
        } catch (Exception e) {
            Cabane.LOGGER.warn("PNJ {} non créé (Easy NPC absent ou fichier invalide) : {}", pnj.file(), e.toString());
            return false;
        }
    }

    /** Kit de départ, une seule fois par joueur. */
    public static void welcome(ServerPlayer player) {
        if (!HubData.get(player.server).markKit(player.getUUID())) {
            return;
        }
        give(player, new ItemStack(CabaneItems.TELEPHONE.get()));
        Item questBook = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("ftbquests", "book"));
        if (questBook != Items.AIR) {
            give(player, new ItemStack(questBook));
        }
        give(player, new ItemStack(Items.BREAD, 8));
        give(player, new ItemStack(Items.TORCH, 16));
        player.sendSystemMessage(Component.literal("Bienvenue à la Cabane !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("• Parle au Vieux Gardien près du feu de camp.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• Ton téléphone : touche N, choisis ton numéro à 3 ou 4 chiffres.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("• Ton livre de quêtes t'attend dans l'inventaire.").withStyle(ChatFormatting.YELLOW));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private HubEvents() {
    }
}
