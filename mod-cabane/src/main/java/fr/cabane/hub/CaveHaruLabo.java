package fr.cabane.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.drogue.Labos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.slf4j.Logger;

/**
 * Le labo « Breaking Célia », sous la salle au trésor de la Cave à Haru (docs/cave-haru/cave.html, tout ce qui suit
 * {@code zone('labo')}). Le coffre-fort du mur ouest de la salle au trésor est une porte de 4 × 4 : le bouton sur
 * l'accoudoir doré du trône la fait descendre dans le sol, et derrière, un puits d'escaliers descend au labo (les postes
 * du mod, deux cuves, la paillasse au cristal bleu, le vestiaire) et à la serre (weed et coca sous des lampes).
 *
 * <p>Il se construit juste après la cave, ou avec {@code /caveharu labo} sous une cave construite avant lui. Le terrain est
 * photographié avant (deux boîtes : tout le dessous, et le haut du puits à côté de la salle au trésor) :
 * {@code /caveharu labo annuler} le remet, {@code /caveharu annuler} enlève le labo puis la cave.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class CaveHaruLabo {
    private static final Logger LOG = LogUtils.getLogger();
    /** Les morceaux de la porte pendant qu'elle glisse : jamais gardés d'un démarrage à l'autre. */
    static final String TAG_PORTE = "cabane_cave_haru_porte";
    private static final String TAG = "cabane_cave_haru";
    private static final ResourceLocation AVANT_BAS = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "cave_haru_labo_avant");
    private static final ResourceLocation AVANT_PUITS = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "cave_haru_labo_puits_avant");

    /** Les deux boîtes du labo (coordonnées du plan) : tout le dessous de la cave, et le haut du puits avec le mur du coffre-fort. */
    private static final int[] BAS = {-9, -29, -2, 21, -14, 20}, PUITS = {-9, -13, -2, 0, -7, 11};
    /** Le bouton sur l'accoudoir doré du trône. */
    private static final int[] BOUTON = {17, -12, 5};
    /** La porte : x 0, z 3..6, y -12..-9 (blocs de fer, la roue en roche noire sculptée au milieu, comme dans le croquis). */
    private static final int PORTE_X = 0, PORTE_Z0 = 3, PORTE_Z1 = 6, PORTE_Y0 = -12, PORTE_Y1 = -9;
    /** La porte descend (ou remonte) en 2 s, reste ouverte 10 s, puis se referme dès que le passage est libre. */
    private static final int GLISSE = 40, OUVERTE = 200;
    private static final int PAR_TICK = 400;

    private static Chantier chantier;

    private enum Etat { FERMEE, DESCEND, OUVERTE, MONTE }

    private static Etat etat = Etat.FERMEE;
    private static long depuis, fermerA;
    private static boolean lance;
    private static final List<Entity> glisseurs = new ArrayList<>();
    private static MinecraftServer serveurPorte;
    private static int compteur;
    /** Ceux qui ont déjà vu le titre « Breaking Célia » depuis le démarrage du serveur. */
    private static final Set<UUID> accueillis = new HashSet<>();

    private CaveHaruLabo() {
    }

    static boolean enChantier() {
        return chantier != null;
    }

    // ================================================================ commandes

    /** /caveharu labo : le labo sous une cave construite avant lui. */
    static int construire(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CaveHaru.Donnees d = CaveHaru.Donnees.get(server);
        if (!d.present) {
            source.sendFailure(Component.literal("Il n'y a pas encore de Cave à Haru (/caveharu d'abord)."));
            return 0;
        }
        if (d.labo) {
            source.sendFailure(Component.literal("Le labo existe déjà. /caveharu labo annuler l'enlève."));
            return 0;
        }
        if (chantier != null || CaveHaru.enChantier()) {
            source.sendFailure(Component.literal("La cave est encore en chantier, attends qu'elle soit finie."));
            return 0;
        }
        ServerLevel level = niveau(server, d);
        if (level == null) {
            source.sendFailure(Component.literal("La dimension de la cave n'existe plus."));
            return 0;
        }
        CaveHaru.Pose pose = d.pose();
        CaveHaru.Plan plan;
        try {
            plan = CaveHaru.lirePlan(server, pose.rot());
        } catch (Exception e) {
            LOG.error("Plan de la Cave à Haru illisible", e);
            source.sendFailure(Component.literal("Le plan du labo est illisible. Rien n'a été touché."));
            return 0;
        }
        return lancer(level, pose, plan, source) ? 1 : 0;
    }

    /** /caveharu labo annuler : le labo seul s'en va, le coffre-fort redevient un coffre-fort. */
    static int annuler(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CaveHaru.Donnees d = CaveHaru.Donnees.get(server);
        ServerLevel level = niveau(server, d);
        if (!d.present || !d.labo || level == null) {
            source.sendFailure(Component.literal("Il n'y a pas de labo à enlever."));
            return 0;
        }
        if (!enlever(level, d)) {
            source.sendFailure(Component.literal("L'état d'origine du terrain sous la cave est introuvable, je n'y touche pas."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Labo enlevé : le coffre-fort de la salle au trésor redevient un simple coffre-fort.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    /** /caveharu labo ouvrir : la porte du coffre-fort descend, comme avec le bouton du trône. */
    static int ouvrirCommande(CommandSourceStack source) {
        CaveHaru.Donnees d = CaveHaru.Donnees.get(source.getServer());
        ServerLevel level = niveau(source.getServer(), d);
        if (!d.present || !d.labo || level == null || chantier != null) {
            source.sendFailure(Component.literal("Il n'y a pas (encore) de labo."));
            return 0;
        }
        ouvrir(level, d.pose(), null);
        return 1;
    }

    private static ServerLevel niveau(MinecraftServer server, CaveHaru.Donnees d) {
        return d.dim.isEmpty() ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d.dim)));
    }

    // ================================================================ construire

    /** Photographie le terrain et lance le chantier du labo. Faux (avec un message) si ce n'est pas possible. */
    static boolean lancer(ServerLevel level, CaveHaru.Pose pose, CaveHaru.Plan plan, CommandSourceStack source) {
        JsonObject o = plan.brut().getAsJsonObject("labo");
        if (o == null) {
            return false;
        }
        String raison = obstacle(level, pose);
        if (raison != null) {
            source.sendFailure(Component.literal("Le labo n'est pas construit : " + raison + ". /caveharu labo pour réessayer."));
            return false;
        }
        StructureTemplateManager gestion = level.getStructureManager();
        if (!photo(level, gestion, AVANT_BAS, boite(pose, BAS)) || !photo(level, gestion, AVANT_PUITS, boite(pose, PUITS))) {
            source.sendFailure(Component.literal("Impossible d'enregistrer l'état du terrain sous la cave. Le labo n'est pas construit."));
            return false;
        }
        CaveHaru.Donnees.get(level.getServer()).labo(true);
        chantier = new Chantier(level, pose, plan, o, source);
        source.sendSuccess(() -> Component.literal("Le labo se construit sous la salle au trésor…").withStyle(ChatFormatting.DARK_GREEN), true);
        return true;
    }

    private static boolean photo(ServerLevel level, StructureTemplateManager gestion, ResourceLocation nom, CaveHaru.Boite b) {
        StructureTemplate t = gestion.getOrCreate(nom);
        t.fillFromWorld(level, b.min(), b.taille(), false, null);
        return gestion.save(nom);
    }

    private static CaveHaru.Boite boite(CaveHaru.Pose pose, int[] b) {
        return pose.boite(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static boolean dans(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /** Quelqu'un dans le sous-sol (on ne construit pas sur lui), ou null. */
    private static String obstacle(ServerLevel level, CaveHaru.Pose pose) {
        for (int[] b : new int[][] {BAS, PUITS}) {
            CaveHaru.Boite boite = boite(pose, b);
            if (level.isOutsideBuildHeight(boite.min()) || level.isOutsideBuildHeight(boite.max())) {
                return "c'est trop près du bas du monde";
            }
            for (Entity e : level.getEntities((Entity) null, boite.aabb(), x -> true)) {
                if (e instanceof Player joueur && !joueur.isSpectator()) {
                    return joueur.getScoreboardName() + " est sous la cave, là où va le labo";
                }
                if (!(e instanceof Player) && CaveHaru.bloquant(e)) {
                    return "il y a " + e.getName().getString() + " en " + CaveHaru.texte(e.blockPosition());
                }
            }
        }
        return null;
    }

    private static final class Chantier {
        final ServerLevel level;
        final CaveHaru.Pose pose;
        final BlockState[] palette;
        final JsonObject o;
        final CommandSourceStack source;
        final int[] structure, finitions, fragiles;
        final Set<Long> planifies = new HashSet<>();
        final List<int[]> volumes = new ArrayList<>();
        final List<BlockPos> deblai = new ArrayList<>();
        /** Les cases fines (vitres, portes, eau…) hors des vides : de la pierre en attendant, pour que la coque soit fermée. */
        final List<BlockPos> provisoires = new ArrayList<>();
        final List<int[]> forces = new ArrayList<>();
        int etape;
        int i;
        int attente;

        Chantier(ServerLevel level, CaveHaru.Pose pose, CaveHaru.Plan plan, JsonObject o, CommandSourceStack source) {
            this.level = level;
            this.pose = pose;
            this.palette = plan.palette();
            this.o = o;
            this.source = source;
            structure = CaveHaru.entiers(o.getAsJsonArray("structure"));
            finitions = CaveHaru.entiers(o.getAsJsonArray("finitions"));
            fragiles = CaveHaru.entiers(o.getAsJsonArray("fragiles"));
            for (int[] l : new int[][] {structure, finitions, fragiles}) {
                for (int k = 0; k < l.length; k += 4) {
                    planifies.add(pose.bloc(l[k], l[k + 1], l[k + 2]).asLong());
                }
            }
            for (JsonElement v : o.getAsJsonArray("vides")) {
                volumes.add(CaveHaru.entiers(v.getAsJsonArray()));
            }
            // on creuse de haut en bas : le sable et le gravier partent avant ce qui les tient
            for (int[] v : volumes) {
                for (int y = v[4]; y >= v[1]; y--) {
                    for (int z = v[2]; z <= v[5]; z++) {
                        for (int x = v[0]; x <= v[3]; x++) {
                            BlockPos p = pose.bloc(x, y, z);
                            if (!planifies.contains(p.asLong())) {
                                deblai.add(p);
                            }
                        }
                    }
                }
            }
            for (int[] l : new int[][] {finitions, fragiles}) {
                for (int k = 0; k < l.length; k += 4) {
                    int x = l[k], y = l[k + 1], z = l[k + 2];
                    if ((dans(BAS, x, y, z) || dans(PUITS, x, y, z)) && volumes.stream().noneMatch(v -> dans(v, x, y, z))) {
                        provisoires.add(pose.bloc(x, y, z));
                    }
                }
            }
        }

        void forcer() {
            for (int[] b : new int[][] {BAS, PUITS}) {
                CaveHaru.Boite boite = boite(pose, b);
                for (int cx = boite.min().getX() >> 4; cx <= boite.max().getX() >> 4; cx++) {
                    for (int cz = boite.min().getZ() >> 4; cz <= boite.max().getZ() >> 4; cz++) {
                        if (level.setChunkForced(cx, cz, true)) {
                            forces.add(new int[] {cx, cz});
                        }
                    }
                }
            }
            // ce qui traîne dans le sous-sol (chauves-souris, monstres des grottes, objets) s'en va
            for (int[] b : new int[][] {BAS, PUITS}) {
                for (Entity e : level.getEntities((Entity) null, boite(pose, b).aabb(), x -> !(x instanceof Player) && !CaveHaru.bloquant(x)
                        && (x instanceof LivingEntity || x instanceof ItemEntity || x instanceof ExperienceOrb))) {
                    e.discard();
                }
            }
        }

        void liberer() {
            for (int[] c : forces) {
                level.setChunkForced(c[0], c[1], false);
            }
            forces.clear();
        }

        /** @return vrai quand tout est fini */
        boolean avancer() {
            final int sansVoisins = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            switch (etape) {
                case 0 -> {
                    // d'abord la coque et tout ce qui est plein, avant de creuser : l'eau d'une nappe voisine reste dehors
                    if (i == 0) {
                        forcer();
                    }
                    int fin = Math.min(i + PAR_TICK * 4, structure.length);
                    for (; i < fin; i += 4) {
                        level.setBlock(pose.bloc(structure[i], structure[i + 1], structure[i + 2]), palette[structure[i + 3]], sansVoisins);
                    }
                    if (i >= structure.length) {
                        for (BlockPos p : provisoires) {
                            level.setBlock(p, Blocks.STONE.defaultBlockState(), sansVoisins);
                        }
                        suivante();
                    }
                }
                case 1 -> {
                    int fin = Math.min(i + PAR_TICK, deblai.size());
                    for (; i < fin; i++) {
                        if (!level.getBlockState(deblai.get(i)).isAir()) {
                            level.setBlock(deblai.get(i), Blocks.AIR.defaultBlockState(), sansVoisins);
                        }
                    }
                    if (i >= deblai.size()) {
                        suivante();
                    }
                }
                case 2 -> {
                    // un dernier passage, au cas où un liquide se serait glissé dans un vide
                    if (++attente < 10) {
                        return false;
                    }
                    for (BlockPos p : deblai) {
                        if (!level.getBlockState(p).isAir()) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), sansVoisins);
                        }
                    }
                    suivante();
                }
                case 3 -> {
                    // escaliers, barreaux, vitres : ils se raccordent à leurs voisins
                    int fin = Math.min(i + PAR_TICK * 4, finitions.length);
                    for (; i < fin; i += 4) {
                        BlockPos p = pose.bloc(finitions[i], finitions[i + 1], finitions[i + 2]);
                        level.setBlock(p, Block.updateFromNeighbourShapes(palette[finitions[i + 3]], level, p), Block.UPDATE_ALL);
                    }
                    if (i >= finitions.length) {
                        suivante();
                    }
                }
                case 4 -> {
                    // portes, plantes, eau, lampes pendues, bougies… tels quels
                    int fin = Math.min(i + PAR_TICK * 4, fragiles.length);
                    for (; i < fin; i += 4) {
                        level.setBlock(pose.bloc(fragiles[i], fragiles[i + 1], fragiles[i + 2]), palette[fragiles[i + 3]], sansVoisins);
                    }
                    if (i >= fragiles.length) {
                        suivante();
                    }
                }
                case 5 -> {
                    postes();
                    suivante();
                }
                default -> {
                    CaveHaru.accrocher(level, pose, o.getAsJsonArray("tableaux"));
                    combinaisons();
                    level.playSound(null, pose.bloc(10, -26, 4), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1F, 0.8F);
                    source.sendSuccess(() -> Component.literal("Le labo « Breaking Célia » est prêt. Le bouton est sur l'accoudoir du trône…")
                            .withStyle(ChatFormatting.DARK_GREEN), true);
                    return true;
                }
            }
            return false;
        }

        private void suivante() {
            etape++;
            i = 0;
        }

        /** Les postes du mod, tournés comme dans le plan, avec leurs cases invisibles. */
        private void postes() {
            for (JsonElement t : o.getAsJsonArray("postes")) {
                JsonArray a = t.getAsJsonArray();
                String id = a.get(3).getAsString();
                Labos.PosteBlock bloc = Labos.BLOCS.stream().map(b -> b.get()).filter(b -> b.poste.id.equals(id)).findFirst().orElse(null);
                if (bloc == null) {
                    LOG.warn("Poste du labo inconnu : {}", id);
                    continue;
                }
                BlockPos p = pose.bloc(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                Direction face = pose.rot().rotate(Direction.byName(a.get(4).getAsString()));
                BlockState etat = bloc.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face);
                level.setBlock(p, etat, Block.UPDATE_ALL);
                for (BlockPos partie : Labos.PosteBlock.parties(p, etat)) {
                    level.setBlock(partie, CabaneBlocks.PARTIE_TABLE.get().defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        /** Les combinaisons jaunes sur leurs porte-armures (on ne les prend pas), et le coffre du vestiaire (on se sert). */
        private void combinaisons() {
            for (JsonElement t : o.getAsJsonArray("armures")) {
                JsonArray a = t.getAsJsonArray();
                Vec3 v = pose.point(a.get(0).getAsInt() + 0.5, a.get(1).getAsInt(), a.get(2).getAsInt() + 0.5);
                ArmorStand support = new ArmorStand(level, v.x, v.y, v.z);
                float yaw = pose.yaw(switch (a.get(3).getAsString()) {
                    case "west" -> 90F;
                    case "north" -> 180F;
                    case "east" -> -90F;
                    default -> 0F;
                });
                support.moveTo(v.x, v.y, v.z, yaw, 0F);
                support.setYHeadRot(yaw);
                support.setYBodyRot(yaw);
                CompoundTag nbt = new CompoundTag();
                support.saveWithoutId(nbt);
                nbt.putInt("DisabledSlots", 4144959);
                nbt.putBoolean("Invulnerable", true);
                support.load(nbt);
                support.setItemSlot(EquipmentSlot.HEAD, combinaison(Items.LEATHER_HELMET, "Cagoule de cuisson"));
                support.setItemSlot(EquipmentSlot.CHEST, combinaison(Items.LEATHER_CHESTPLATE, "Combinaison de cuisson"));
                support.setItemSlot(EquipmentSlot.LEGS, combinaison(Items.LEATHER_LEGGINGS, "Pantalon de cuisson"));
                support.setItemSlot(EquipmentSlot.FEET, combinaison(Items.LEATHER_BOOTS, "Bottes de cuisson"));
                support.addTag(TAG);
                level.addFreshEntity(support);
            }
            for (JsonElement t : o.getAsJsonArray("vestiaires")) {
                JsonArray a = t.getAsJsonArray();
                BlockPos p = pose.bloc(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                if (level.getBlockEntity(p) instanceof Container coffre) {
                    int slot = 0;
                    for (int k = 0; k < 4 && slot + 4 <= coffre.getContainerSize(); k++) {
                        coffre.setItem(slot++, combinaison(Items.LEATHER_HELMET, "Cagoule de cuisson"));
                        coffre.setItem(slot++, combinaison(Items.LEATHER_CHESTPLATE, "Combinaison de cuisson"));
                        coffre.setItem(slot++, combinaison(Items.LEATHER_LEGGINGS, "Pantalon de cuisson"));
                        coffre.setItem(slot++, combinaison(Items.LEATHER_BOOTS, "Bottes de cuisson"));
                    }
                    coffre.setChanged();
                }
            }
        }
    }

    private static ItemStack combinaison(Item objet, String nom) {
        ItemStack s = new ItemStack(objet);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(0xF2C230, false));
        s.set(DataComponents.ITEM_NAME, Component.literal(nom).withStyle(ChatFormatting.YELLOW));
        return s;
    }

    // ================================================================ enlever

    /**
     * Remet le terrain d'avant le labo (et le coffre-fort d'avant). Vrai s'il n'y avait pas de labo ou s'il est parti ;
     * faux si ses photos sont introuvables (rien n'est touché).
     */
    static boolean enlever(ServerLevel level, CaveHaru.Donnees d) {
        if (chantier != null) {
            chantier.liberer();
            chantier = null;
        }
        arreterPorte();
        if (!d.labo) {
            return true;
        }
        StructureTemplateManager gestion = level.getStructureManager();
        Optional<StructureTemplate> bas = gestion.get(AVANT_BAS), puits = gestion.get(AVANT_PUITS);
        if (bas.isEmpty() || puits.isEmpty()) {
            return false;
        }
        CaveHaru.Pose pose = d.pose();
        List<AABB> zones = new ArrayList<>();
        for (int[] b : new int[][] {BAS, PUITS}) {
            CaveHaru.Boite boite = boite(pose, b);
            zones.add(boite.aabb());
            for (Entity e : level.getEntities((Entity) null, boite.aabb(), x -> x.getTags().contains(TAG) || x.getTags().contains(TAG_PORTE) || x instanceof ItemEntity)) {
                e.discard();
            }
            // les coffres et tonneaux se vident sans rien jeter par terre
            for (BlockPos p : BlockPos.betweenClosed(boite.min(), boite.max())) {
                if (level.getBlockEntity(p) instanceof Container coffre) {
                    coffre.clearContent();
                }
            }
        }
        StructureTemplate[] photos = {bas.get(), puits.get()};
        int[][] boites = {BAS, PUITS};
        for (int k = 0; k < 2; k++) {
            BlockPos min = boite(pose, boites[k]).min();
            photos[k].placeInWorld(level, min, min, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        BlockPos bouton = pose.bloc(BOUTON[0], BOUTON[1], BOUTON[2]);
        if (level.getBlockState(bouton).is(Blocks.POLISHED_BLACKSTONE_BUTTON)) {
            level.setBlock(bouton, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (AABB zone : zones) {
            for (Entity e : level.getEntities((Entity) null, zone, x -> x instanceof ItemEntity)) {
                e.discard(); // les bougies, lanternes et tapis décrochés
            }
        }
        d.labo(false);
        return true;
    }

    // ================================================================ la porte du coffre-fort

    private static boolean porte(int[] c) {
        return c[0] == PORTE_X && c[2] >= PORTE_Z0 && c[2] <= PORTE_Z1 && c[1] >= PORTE_Y0 && c[1] <= PORTE_Y1;
    }

    private static boolean bouton(int[] c) {
        return c[0] == BOUTON[0] && c[1] == BOUTON[1] && c[2] == BOUTON[2];
    }

    /** Le bloc de la porte fermée à cette case du plan. */
    private static BlockState etatPorte(int y, int z) {
        return (z == 4 || z == 5) && (y == -11 || y == -10) ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
    }

    /** Sur le palier ou en haut de l'escalier, derrière la porte (coordonnées du plan, pieds du joueur) : la porte s'ouvre. */
    private static boolean remonte(int[] c) {
        return (c[0] == -1 || c[0] == -2) && c[2] >= 3 && c[2] <= 8 && c[1] >= -15 && c[1] <= -11;
    }

    /** Dans le labo ou la serre (coordonnées du plan, pieds du joueur) : le titre, la première fois. */
    private static boolean dansLeLabo(int[] c) {
        return c[1] >= -27 && c[1] <= -22 && ((c[0] >= 1 && c[0] <= 20 && c[2] >= -1 && c[2] <= 10) || (c[0] >= 2 && c[0] <= 19 && c[2] >= 12 && c[2] <= 19));
    }

    /** Le labo construit dans ce monde-là (avec sa cave), ou null. */
    private static CaveHaru.Pose ici(Level level) {
        CaveHaru.Pose pose = CaveHaru.ici(level);
        return pose != null && CaveHaru.Donnees.get(((ServerLevel) level).getServer()).labo ? pose : null;
    }

    @SubscribeEvent
    public static void clic(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getLevel() instanceof ServerLevel level) || chantier != null) {
            return;
        }
        CaveHaru.Pose pose = ici(level);
        if (pose == null) {
            return;
        }
        int[] c = pose.plan(event.getPos());
        if (bouton(c)) {
            ouvrir(level, pose, event.getEntity()); // le bouton s'enfonce comme un bouton
        } else if (porte(c)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (pose.plan(event.getEntity().blockPosition())[0] < PORTE_X) {
                ouvrir(level, pose, event.getEntity()); // de l'intérieur, la porte s'ouvre à la main
            } else {
                event.getEntity().displayClientMessage(Component.literal("Verrouillé. Ça ne s'ouvre pas à la main…").withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    private static void ouvrir(ServerLevel level, CaveHaru.Pose pose, Player joueur) {
        long t = level.getGameTime();
        if (etat == Etat.OUVERTE) {
            fermerA = Math.max(fermerA, t + OUVERTE);
            return;
        }
        if (etat != Etat.FERMEE) {
            return;
        }
        for (int y = PORTE_Y0; y <= PORTE_Y1; y++) {
            for (int z = PORTE_Z0; z <= PORTE_Z1; z++) {
                BlockPos p = pose.bloc(PORTE_X, y, z);
                BlockState s = level.getBlockState(p);
                glisseur(level, p, s.isAir() ? etatPorte(y, z) : s);
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        etat = Etat.DESCEND;
        depuis = t;
        lance = false;
        BlockPos milieu = pose.bloc(PORTE_X, -11, 4);
        level.playSound(null, milieu, SoundEvents.VAULT_OPEN_SHUTTER, SoundSource.BLOCKS, 1.6F, 0.6F);
        level.playSound(null, milieu, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.2F, 0.5F);
        if (joueur != null) {
            joueur.displayClientMessage(Component.literal("Le coffre-fort s'enfonce dans le sol…").withStyle(ChatFormatting.GOLD), true);
        }
    }

    private static void fermer(ServerLevel level, CaveHaru.Pose pose) {
        for (int y = PORTE_Y0; y <= PORTE_Y1; y++) {
            for (int z = PORTE_Z0; z <= PORTE_Z1; z++) {
                glisseur(level, pose.bloc(PORTE_X, y, z).below(PORTE_Y1 - PORTE_Y0 + 1), etatPorte(y, z));
            }
        }
        etat = Etat.MONTE;
        depuis = level.getGameTime();
        lance = false;
        BlockPos milieu = pose.bloc(PORTE_X, -11, 4);
        level.playSound(null, milieu, SoundEvents.VAULT_CLOSE_SHUTTER, SoundSource.BLOCKS, 1.6F, 0.6F);
        level.playSound(null, milieu, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.2F, 0.5F);
    }

    /** Un morceau de porte qui glisse (un bloc affiché, sans collision), le temps de l'animation. */
    private static void glisseur(ServerLevel level, BlockPos p, BlockState etatBloc) {
        CompoundTag t = new CompoundTag();
        t.putString("id", "minecraft:block_display");
        t.put("block_state", NbtUtils.writeBlockState(etatBloc));
        t.putInt("teleport_duration", GLISSE);
        CompoundTag lumiere = new CompoundTag();
        lumiere.putInt("block", 13);
        lumiere.putInt("sky", 0);
        t.put("brightness", lumiere);
        Entity e = EntityType.loadEntityRecursive(t, level, x -> {
            x.moveTo(p.getX(), p.getY(), p.getZ(), 0F, 0F);
            return x;
        });
        if (e != null) {
            e.addTag(TAG_PORTE);
            level.addFreshEntity(e);
            glisseurs.add(e);
        }
    }

    private static void arreterPorte() {
        for (Entity e : glisseurs) {
            e.discard();
        }
        glisseurs.clear();
        etat = Etat.FERMEE;
    }

    private static boolean occupe(ServerLevel level, CaveHaru.Pose pose) {
        AABB passage = new AABB(pose.bloc(PORTE_X, PORTE_Y0, PORTE_Z0)).minmax(new AABB(pose.bloc(PORTE_X, PORTE_Y1, PORTE_Z1)));
        return !level.getEntitiesOfClass(LivingEntity.class, passage).isEmpty();
    }

    static void tick(MinecraftServer server) {
        if (chantier != null) {
            if (chantier.level.getServer() != server) {
                chantier = null;
            } else {
                try {
                    if (chantier.avancer()) {
                        chantier.liberer();
                        chantier = null;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Chantier du labo de la Cave à Haru interrompu", e);
                    chantier.source.sendFailure(Component.literal("Le chantier du labo s'est arrêté sur une erreur. /caveharu labo annuler remet le terrain."));
                    chantier.liberer();
                    chantier = null;
                }
            }
            return;
        }
        if (serveurPorte != server) {
            serveurPorte = server;
            glisseurs.clear();
            etat = Etat.FERMEE;
            accueillis.clear();
        }
        compteur++;
        if (etat == Etat.FERMEE && compteur % 10 != 0) {
            return;
        }
        CaveHaru.Donnees d = CaveHaru.Donnees.get(server);
        ServerLevel level = d.present && d.labo ? niveau(server, d) : null;
        CaveHaru.Pose pose = d.pose();
        BlockPos repere = level == null ? null : pose.bloc(PORTE_X, -10, 4);
        if (level == null || !level.isLoaded(repere)) {
            if (etat != Etat.FERMEE) {
                arreterPorte(); // la porte se remettra toute seule
            }
            return;
        }
        long t = level.getGameTime();
        switch (etat) {
            case FERMEE -> {
                for (ServerPlayer p : level.players()) {
                    if (!p.isSpectator() && p.distanceToSqr(repere.getCenter()) < 100 && remonte(pose.plan(p.blockPosition()))) {
                        ouvrir(level, pose, null);
                        return;
                    }
                }
                if (compteur % 100 == 0) {
                    reparer(level, pose);
                }
                accueil(level, pose);
            }
            case DESCEND, MONTE -> {
                boolean descend = etat == Etat.DESCEND;
                if (!lance && t >= depuis + 3) {
                    // les blocs affichés sont arrivés chez les joueurs : on les fait glisser de 4 blocs
                    for (Entity e : glisseurs) {
                        e.teleportTo(e.getX(), e.getY() + (descend ? -4 : 4), e.getZ());
                    }
                    lance = true;
                }
                if (lance && (t - depuis) % 8 == 0 && t < depuis + 3 + GLISSE) {
                    level.playSound(null, pose.bloc(PORTE_X, -11, 4), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.8F, 0.5F);
                }
                if (t >= depuis + 3 + GLISSE + 2) {
                    if (!descend) {
                        poserPorte(level, pose);
                        level.playSound(null, pose.bloc(PORTE_X, -11, 4), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.4F, 0.5F);
                    }
                    for (Entity e : glisseurs) {
                        e.discard();
                    }
                    glisseurs.clear();
                    etat = descend ? Etat.OUVERTE : Etat.FERMEE;
                    fermerA = t + OUVERTE;
                }
            }
            case OUVERTE -> {
                if (compteur % 10 == 0) {
                    for (ServerPlayer p : level.players()) {
                        if (!p.isSpectator() && remonte(pose.plan(p.blockPosition()))) {
                            fermerA = Math.max(fermerA, t + 60); // quelqu'un attend sur le palier
                        }
                    }
                    accueil(level, pose);
                }
                if (t >= fermerA && !occupe(level, pose)) {
                    fermer(level, pose);
                }
            }
        }
    }

    /** La porte refermée : ses blocs reviennent là où la place est libre (le reste, à la prochaine réparation). */
    private static void poserPorte(ServerLevel level, CaveHaru.Pose pose) {
        for (int y = PORTE_Y0; y <= PORTE_Y1; y++) {
            for (int z = PORTE_Z0; z <= PORTE_Z1; z++) {
                BlockPos p = pose.bloc(PORTE_X, y, z);
                if (level.getBlockState(p).canBeReplaced() && level.getEntitiesOfClass(LivingEntity.class, new AABB(p)).isEmpty()) {
                    level.setBlock(p, etatPorte(y, z), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Cassée (en créatif) ou restée ouverte après un redémarrage : la porte revient. */
    private static void reparer(ServerLevel level, CaveHaru.Pose pose) {
        poserPorte(level, pose);
    }

    /** Le titre « Breaking Célia », la première fois qu'on met les pieds dans le labo depuis le démarrage du serveur. */
    private static void accueil(ServerLevel level, CaveHaru.Pose pose) {
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && !accueillis.contains(p.getUUID()) && dansLeLabo(pose.plan(p.blockPosition()))) {
                accueillis.add(p.getUUID());
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("le labo d'Haru").withStyle(ChatFormatting.GRAY)));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Breaking Célia").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.BOLD)));
            }
        }
    }

    /** La porte et son bouton ne se cassent pas (sauf en créatif). */
    @SubscribeEvent
    public static void casser(BlockEvent.BreakEvent event) {
        if (event.getPlayer().isCreative() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        CaveHaru.Pose pose = ici(level);
        if (pose != null) {
            int[] c = pose.plan(event.getPos());
            if (porte(c) || bouton(c)) {
                event.setCanceled(true);
                event.getPlayer().displayClientMessage(Component.literal("Ça ne se casse pas.").withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    @SubscribeEvent
    public static void explosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CaveHaru.Pose pose = ici(level);
            if (pose != null) {
                event.getAffectedBlocks().removeIf(p -> {
                    int[] c = pose.plan(p);
                    return porte(c) || bouton(c);
                });
            }
        }
    }
}
