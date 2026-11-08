package fr.cabane.backrooms;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.MapCodec;
import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les backrooms (/backroom), d'après l'aperçu validé (docs/backrooms) : l'hôte tape /backroom et tout le monde (ou les
 * joueurs nommés) voit ses yeux se fermer, s'endort… et se réveille seul, au hasard, dans le niveau 0 : un labyrinthe
 * jaune infini (cabane:backrooms, {@link GenerateurBackrooms}) où rien ne se casse. Des monstres errent et foncent sur
 * ceux qu'ils voient ; on se cache dans les casiers ; celui qui se fait attraper a un screamer, rien que pour lui, et se
 * réveille ailleurs. Mourir aussi renvoie ailleurs dans les backrooms ; les portes EXIT sont des pièges. Personne ne sort
 * tout seul : quand l'hôte tape /backroom fin, les yeux se rouvrent, chacun est pile là où il était, avec tout ce qu'il
 * avait sur lui, et l'écran dit « Quel rêve étrange ! ».
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Backrooms {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("backrooms"));

    /** Les atterrissages se font dans un carré de 400 × 400 blocs autour de l'origine (en pièces de 6). */
    private static final int RAYON_PIECES = 33;
    /** Combien de monstres autour de chaque joueur (dans un rayon de 40 blocs) : à peu près un pour 30 × 30. */
    private static final int MONSTRES_AUTOUR = 4;

    // ---------------------------------------------------------------- les blocs : rien ne se casse
    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);

    private static BlockBehaviour.Properties dur(MapColor couleur, SoundType son) {
        return BlockBehaviour.Properties.of().mapColor(couleur).strength(-1F, 3_600_000F).sound(son).noLootTable().pushReaction(PushReaction.BLOCK)
                .isValidSpawn((s, l, p, t) -> false);
    }

    public static final DeferredBlock<Block> MUR = BLOCS.registerBlock("backrooms_mur", Block::new, dur(MapColor.SAND, SoundType.WOOD));
    public static final DeferredBlock<Block> PLINTHE = BLOCS.registerBlock("backrooms_plinthe", Block::new, dur(MapColor.SAND, SoundType.WOOD));
    public static final DeferredBlock<Block> MOQUETTE = BLOCS.registerBlock("backrooms_moquette", Block::new, dur(MapColor.TERRACOTTA_YELLOW, SoundType.WOOL));
    public static final DeferredBlock<Block> PLAFOND = BLOCS.registerBlock("backrooms_plafond", Block::new, dur(MapColor.QUARTZ, SoundType.STONE));
    public static final DeferredBlock<Block> NEON = BLOCS.registerBlock("backrooms_neon", Block::new,
            dur(MapColor.SNOW, SoundType.GLASS).lightLevel(s -> 15));
    public static final DeferredBlock<Block> NEON_CLIGNOTE = BLOCS.registerBlock("backrooms_neon_clignote", Block::new,
            dur(MapColor.SNOW, SoundType.GLASS).lightLevel(s -> 13));
    public static final DeferredBlock<Block> NEON_MORT = BLOCS.registerBlock("backrooms_neon_mort", Block::new, dur(MapColor.COLOR_GRAY, SoundType.GLASS));
    public static final DeferredBlock<BlocsBackrooms.Casier> CASIER = BLOCS.registerBlock("backrooms_casier", BlocsBackrooms.Casier::new,
            dur(MapColor.COLOR_GREEN, SoundType.METAL).isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false));
    public static final DeferredBlock<BlocsBackrooms.Porte> PORTE = BLOCS.registerBlock("backrooms_porte", BlocsBackrooms.Porte::new,
            dur(MapColor.METAL, SoundType.METAL));
    public static final DeferredBlock<BlocsBackrooms.Panneau> EXIT = BLOCS.registerBlock("backrooms_exit", BlocsBackrooms.Panneau::new,
            dur(MapColor.SAND, SoundType.WOOD).lightLevel(s -> 10));

    // ---------------------------------------------------------------- les monstres, la place dans le casier
    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final Map<Monstre.Sorte, DeferredHolder<EntityType<?>, EntityType<Monstre>>> MONSTRES = new EnumMap<>(Monstre.Sorte.class);

    static {
        for (Monstre.Sorte s : Monstre.Sorte.values()) {
            String id = "backrooms_" + s.id;
            MONSTRES.put(s, ENTITES.register(id, () -> EntityType.Builder.<Monstre>of((t, l) -> new Monstre(t, l, s), MobCategory.MISC)
                    .sized(s.largeur, s.hauteur).clientTrackingRange(10).updateInterval(2).build(id)));
        }
    }

    public static final DeferredHolder<EntityType<?>, EntityType<Cachette>> CACHETTE = ENTITES.register("backrooms_cachette",
            () -> EntityType.Builder.<Cachette>of(Cachette::new, MobCategory.MISC).sized(0.4F, 0.4F).clientTrackingRange(8).noSummon()
                    .build("backrooms_cachette"));

    // ---------------------------------------------------------------- les sons (synthétisés par outils/backrooms.mjs)
    private static final DeferredRegister<SoundEvent> SONS = DeferredRegister.create(Registries.SOUND_EVENT, Cabane.MODID);

    private static DeferredHolder<SoundEvent, SoundEvent> son(String nom) {
        return SONS.register("backrooms." + nom, () -> SoundEvent.createVariableRangeEvent(Cabane.id("backrooms." + nom)));
    }

    public static final DeferredHolder<SoundEvent, SoundEvent> SON_BOURDON = son("bourdon");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_REPERE = son("repere");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_COEUR = son("coeur");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_CASIER = son("casier");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_RENIFLE = son("renifle");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_GRESILLE = son("gresille");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_PAS = son("pas");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_GRIFFE = son("griffe");
    public static final Map<Monstre.Sorte, DeferredHolder<SoundEvent, SoundEvent>> CRIS = new EnumMap<>(Monstre.Sorte.class);

    static {
        for (Monstre.Sorte s : Monstre.Sorte.values()) {
            CRIS.put(s, son("cri." + s.id));
        }
    }

    // ---------------------------------------------------------------- le générateur du labyrinthe
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATEURS = DeferredRegister.create(Registries.CHUNK_GENERATOR, Cabane.MODID);

    static {
        GENERATEURS.register("backrooms", () -> GenerateurBackrooms.CODEC);
    }

    public static void register(IEventBus modBus) {
        BLOCS.register(modBus);
        ENTITES.register(modBus);
        SONS.register(modBus);
        GENERATEURS.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> MONSTRES.forEach((s, t) -> e.put(t.get(), Monstre.attributs(s).build())));
        modBus.addListener(Backrooms::paquets);
    }

    // ---------------------------------------------------------------- ce que voit le joueur

    /** Ce qui s'affiche sur l'écran d'un joueur (ClientBackrooms). */
    public static final byte ENDORMIR = 0, ARRIVEE = 1, SCREAMER = 2, PIEGE = 3, ASSOMME = 4, FERMER = 5, REVEIL = 6, REPERE = 7, SCREAMER_TEST = 8;

    public record Ecran(byte genre, byte sorte) implements CustomPacketPayload {
        public static final Type<Ecran> TYPE = new Type<>(Cabane.id("backrooms_ecran"));
        public static final StreamCodec<ByteBuf, Ecran> CODEC = StreamCodec.composite(ByteBufCodecs.BYTE, Ecran::genre, ByteBufCodecs.BYTE, Ecran::sorte,
                Ecran::new);

        @Override
        public Type<Ecran> type() {
            return TYPE;
        }
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Ecran.TYPE, Ecran.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.backrooms.ClientBackrooms.ecran(p)));
    }

    private static void ecran(ServerPlayer joueur, byte genre, int sorte) {
        PacketDistributor.sendToPlayer(joueur, new Ecran(genre, (byte) sorte));
    }

    // ---------------------------------------------------------------- les temps (en ticks)
    /** Les yeux qui se ferment avant de tomber dans les backrooms. */
    public static final int ENDORMISSEMENT = 80;
    /** Les yeux qui se ferment à la fin, avant de se réveiller chez soi. */
    public static final int FERMETURE = 36;
    private static final int TEMPS_PIEGE = 34, TEMPS_ASSOMME = 30, GRACE = 70;

    /** Ce qui attend chaque joueur, avec le nombre de ticks qui restent. */
    private enum Attente { ENTRER, SCREAMER, PIEGE, ASSOMME, SORTIR }

    private record EnAttente(Attente quoi, int reste) {
    }

    private static final Map<UUID, EnAttente> ATTENTES = new HashMap<>();
    private static final Map<UUID, Long> GRACES = new HashMap<>();

    // ---------------------------------------------------------------- entrer, sortir

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    /** Un monstre peut le voir, le poursuivre et l'attraper. */
    public static boolean chassable(ServerPlayer j) {
        return dedans(j) && j.isAlive() && !j.isSpectator() && !(j.getVehicle() instanceof Cachette) && !ATTENTES.containsKey(j.getUUID())
                && GRACES.getOrDefault(j.getUUID(), 0L) <= j.serverLevel().getGameTime();
    }

    /** L'hôte lance le rêve : les yeux se ferment, puis chacun tombe dans les backrooms. */
    static int envoyer(MinecraftServer server, Collection<ServerPlayer> joueurs) {
        ServerLevel br = niveau(server);
        if (br == null) {
            return -1;
        }
        BackroomsData.get(server).setActif(true);
        int n = 0;
        for (ServerPlayer j : joueurs) {
            if (dedans(j) || ATTENTES.containsKey(j.getUUID()) || j.isSpectator()) {
                continue;
            }
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            ATTENTES.put(j.getUUID(), new EnAttente(Attente.ENTRER, ENDORMISSEMENT));
            ecran(j, ENDORMIR, 0);
            n++;
        }
        return n;
    }

    /** Il tombe dans les backrooms : on retient d'où il vient et ce qu'il a sur lui, et il atterrit au hasard. */
    private static void entrer(ServerPlayer j) {
        MinecraftServer server = j.server;
        ServerLevel br = niveau(server);
        if (br == null) {
            return;
        }
        BackroomsData data = BackroomsData.get(server);
        if (data.avant(j.getUUID()) == null) {
            data.setAvant(j.getUUID(), new BackroomsData.Avant(j.level().dimension().location(), j.getX(), j.getY(), j.getZ(), j.getYRot(),
                    j.getXRot(), j.gameMode.getGameModeForPlayer(), j.getInventory().save(new net.minecraft.nbt.ListTag()), j.experienceLevel,
                    j.experienceProgress, j.getHealth(), j.getFoodData().getFoodLevel(), j.getFoodData().getSaturationLevel()));
        }
        // on n'emporte rien dans le rêve : tout est rendu au réveil
        j.getInventory().clearContent();
        j.inventoryMenu.broadcastChanges();
        j.stopRiding();
        placer(j, br);
        verifierMode(j);
        ecran(j, ARRIVEE, 0);
        j.playNotifySound(SON_GRESILLE.get(), SoundSource.MASTER, 1F, 1F);
    }

    /** Il se réveille chez lui, pile là où il était, avec tout ce qu'il avait. */
    private static void sortir(ServerPlayer j) {
        MinecraftServer server = j.server;
        BackroomsData data = BackroomsData.get(server);
        BackroomsData.Avant a = data.avant(j.getUUID());
        j.stopRiding();
        j.setInvisible(j.hasEffect(MobEffects.INVISIBILITY));
        if (a == null) {
            if (dedans(j)) {
                ServerLevel monde = server.overworld();
                BlockPos p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
                j.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, j.getYRot(), 0F);
            }
            ecran(j, REVEIL, 0);
            return;
        }
        ServerLevel cible = server.getLevel(ResourceKey.create(Registries.DIMENSION, a.dimension()));
        if (cible == null || cible.dimension() == DIMENSION) {
            cible = server.overworld();
        }
        j.teleportTo(cible, a.x(), a.y(), a.z(), a.yaw(), a.pitch());
        j.fallDistance = 0;
        j.getInventory().load(a.inventaire());
        j.experienceProgress = a.progres();
        j.setExperienceLevels(a.niveau());
        j.setHealth(Math.max(1F, Math.min(a.vie(), j.getMaxHealth())));
        j.getFoodData().setFoodLevel(a.faim());
        j.getFoodData().setSaturation(a.saturation());
        if (j.gameMode.getGameModeForPlayer() != a.mode()) {
            j.setGameMode(a.mode());
        }
        j.inventoryMenu.broadcastChanges();
        data.setAvant(j.getUUID(), null);
        ecran(j, REVEIL, 0);
    }

    /** L'hôte arrête le rêve : les yeux se ferment dans les backrooms, et tout le monde se réveille chez soi. */
    static int finir(MinecraftServer server) {
        BackroomsData data = BackroomsData.get(server);
        int n = 0;
        for (ServerPlayer j : server.getPlayerList().getPlayers()) {
            EnAttente e = ATTENTES.get(j.getUUID());
            if (e != null && e.quoi() == Attente.ENTRER) {
                // il ne s'était pas encore endormi : il rouvre les yeux sur place
                ATTENTES.remove(j.getUUID());
                ecran(j, REVEIL, 0);
                n++;
            } else if (dedans(j) || data.avant(j.getUUID()) != null) {
                j.stopRiding();
                ATTENTES.put(j.getUUID(), new EnAttente(Attente.SORTIR, FERMETURE));
                ecran(j, FERMER, 0);
                n++;
            }
        }
        data.setActif(false);
        ServerLevel br = niveau(server);
        if (br != null) {
            for (Monstre m : br.getEntities(EntityTypeTest.forClass(Monstre.class), m -> true)) {
                m.discard();
            }
        }
        return n;
    }

    /** Un endroit au hasard : une pièce éclairée avec un casier pas loin, loin des autres joueurs et des monstres. */
    private static void placer(ServerPlayer j, ServerLevel br) {
        RandomSource r = j.getRandom();
        List<Vec3> autres = new ArrayList<>();
        for (ServerPlayer p : br.players()) {
            if (p != j) {
                autres.add(p.position());
            }
        }
        for (Monstre m : br.getEntities(EntityTypeTest.forClass(Monstre.class), m -> true)) {
            autres.add(m.position());
        }
        int bi = 0, bk = 0;
        for (int essai = 0; essai < 600; essai++) {
            int i = r.nextInt(RAYON_PIECES * 2 + 1) - RAYON_PIECES, k = r.nextInt(RAYON_PIECES * 2 + 1) - RAYON_PIECES;
            if (Labyrinthe.neon(i, k) != Labyrinthe.NEON_ALLUME) {
                continue;
            }
            if (Labyrinthe.casier(i, k) == null && Labyrinthe.casier(i + 1, k) == null && Labyrinthe.casier(i - 1, k) == null
                    && Labyrinthe.casier(i, k + 1) == null && Labyrinthe.casier(i, k - 1) == null) {
                continue;
            }
            bi = i;
            bk = k;
            double loin = essai < 200 ? 40 : essai < 400 ? 20 : 0;
            double x = i * Labyrinthe.P + 3.5, z = k * Labyrinthe.P + 3.5;
            if (autres.stream().allMatch(a -> Math.hypot(a.x - x, a.z - z) > loin)) {
                break;
            }
        }
        double x = bi * Labyrinthe.P + 3.5, z = bk * Labyrinthe.P + 3.5;
        // on se réveille face au couloir le plus long
        float mieux = 0;
        double loin = -1;
        for (int a = 0; a < 16; a++) {
            float yaw = a * 22.5F;
            double fx = -Mth.sin(yaw * Mth.DEG_TO_RAD), fz = Mth.cos(yaw * Mth.DEG_TO_RAD);
            double d = 0;
            while (d < 30 && Labyrinthe.marchable(Mth.floor(x + fx * d), Mth.floor(z + fz * d))) {
                d += 0.25;
            }
            if (d > loin) {
                loin = d;
                mieux = yaw;
            }
        }
        j.stopRiding();
        j.teleportTo(br, x, 1.0, z, mieux, 0F);
        j.fallDistance = 0;
        j.setDeltaMovement(Vec3.ZERO);
        GRACES.put(j.getUUID(), br.getGameTime() + GRACE);
    }

    /** Dans les backrooms, un joueur en survie passe en aventure (on n'y casse rien, on n'y pose rien). */
    private static void verifierMode(ServerPlayer j) {
        if (dedans(j) && j.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
            j.setGameMode(GameType.ADVENTURE);
        }
    }

    // ---------------------------------------------------------------- ce qui se passe dedans

    /** Un monstre vient de le voir : le grincement, rien que pour lui. */
    static void repere(ServerPlayer j, Monstre m) {
        j.playNotifySound(SON_REPERE.get(), SoundSource.HOSTILE, 1F, 1F);
        ecran(j, REPERE, m.sorte().ordinal());
    }

    static void renifle(ServerLevel monde, Monstre m) {
        monde.playSound(null, m.getX(), m.getY() + 1, m.getZ(), SON_RENIFLE.get(), SoundSource.HOSTILE, 0.9F, 0.85F + m.getRandom().nextFloat() * 0.3F);
    }

    /** Attrapé : le screamer, rien que pour lui ; il se réveille ailleurs quand l'écran est noir. */
    static void attraper(ServerPlayer j, Monstre m) {
        if (!chassable(j)) {
            return;
        }
        Monstre.Sorte s = m.sorte();
        int duree = (int) Math.round((s.debut() + 3.0) * 20);
        ATTENTES.put(j.getUUID(), new EnAttente(Attente.SCREAMER, duree));
        j.setSprinting(false);
        ecran(j, SCREAMER, s.ordinal());
        for (Monstre autre : j.serverLevel().getEntities(EntityTypeTest.forClass(Monstre.class), x -> x.distanceToSqr(j) < 48 * 48)) {
            if (autre.etat() != Monstre.ERRE) {
                autre.oublier();
            }
        }
    }

    /** Clic droit sur un casier : on s'y cache. */
    static void cacher(ServerPlayer j, BlockPos bas, Direction devant) {
        if (j.isPassenger() || ATTENTES.containsKey(j.getUUID())) {
            return;
        }
        ServerLevel monde = j.serverLevel();
        if (!monde.getEntitiesOfClass(Cachette.class, new net.minecraft.world.phys.AABB(bas)).isEmpty()) {
            j.displayClientMessage(Component.literal("Il y a déjà quelqu'un dedans.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int sens = 0;
        for (int s = 0; s < 4; s++) {
            if (Labyrinthe.DIRECTIONS[s] == devant) {
                sens = s;
            }
        }
        Cachette c = Cachette.dans(monde, bas, sens);
        monde.addFreshEntity(c);
        j.setSprinting(false);
        j.startRiding(c, true);
        monde.playSound(null, bas.getX() + 0.5, bas.getY() + 1, bas.getZ() + 0.5, SON_CASIER.get(), SoundSource.BLOCKS, 0.8F, 1F);
        for (Monstre m : monde.getEntities(EntityTypeTest.forClass(Monstre.class), x -> x.distanceToSqr(j) < 48 * 48)) {
            m.cacheIci(j.getUUID(), c.devant());
        }
    }

    /** La porte EXIT : ce n'était pas la sortie. */
    static void piege(ServerPlayer j, BlockPos bas) {
        if (!dedans(j) || ATTENTES.containsKey(j.getUUID())) {
            return;
        }
        j.serverLevel().playSound(null, bas, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1F, 0.8F);
        ATTENTES.put(j.getUUID(), new EnAttente(Attente.PIEGE, TEMPS_PIEGE));
        ecran(j, PIEGE, 0);
    }

    /** Un but de balade pour un monstre qui erre : une case libre à 6 – 30 blocs (le Souriant reste dans le noir, la Phalène près des néons). */
    static BlockPos balade(Monstre m) {
        RandomSource r = m.getRandom();
        for (int essai = 0; essai < 24; essai++) {
            double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 24;
            int x = Mth.floor(m.getX() + Math.cos(a) * d), z = Mth.floor(m.getZ() + Math.sin(a) * d);
            if (convient(m.sorte(), x, z) && m.level().hasChunkAt(new BlockPos(x, 1, z))) {
                return new BlockPos(x, 1, z);
            }
        }
        return null;
    }

    private static boolean convient(Monstre.Sorte s, int x, int z) {
        if (!Labyrinthe.marchable(x, z)) {
            return false;
        }
        int i = Labyrinthe.fdiv(x, Labyrinthe.P), k = Labyrinthe.fdiv(z, Labyrinthe.P);
        return switch (s) {
            case SOURIANT -> Labyrinthe.sombre(i, k);
            case PHALENE -> Labyrinthe.eclairee(i, k) && !Labyrinthe.sombre(i, k);
            default -> true;
        };
    }

    /** Les monstres : trois Rampeurs, trois Souriants, deux Fêtards, deux Sans-Visage, deux Phalènes sur douze. */
    private static final Monstre.Sorte[] TIRAGE = {Monstre.Sorte.RAMPEUR, Monstre.Sorte.RAMPEUR, Monstre.Sorte.RAMPEUR, Monstre.Sorte.SOURIANT,
            Monstre.Sorte.SOURIANT, Monstre.Sorte.SOURIANT, Monstre.Sorte.FETARD, Monstre.Sorte.FETARD, Monstre.Sorte.SANSVISAGE,
            Monstre.Sorte.SANSVISAGE, Monstre.Sorte.PHALENE, Monstre.Sorte.PHALENE};

    /** Garde quelques monstres autour de chaque joueur, et enlève ceux qui sont trop loin de tout le monde. */
    private static void peupler(ServerLevel br) {
        List<ServerPlayer> joueurs = br.players().stream().filter(p -> !p.isSpectator()).toList();
        List<Monstre> monstres = new ArrayList<>(br.getEntities(EntityTypeTest.forClass(Monstre.class), m -> true));
        for (Monstre m : monstres) {
            // loin de tout le monde (spectateurs compris) : il disparaît
            double d = br.players().stream().mapToDouble(p -> p.distanceToSqr(m)).min().orElse(Double.MAX_VALUE);
            if (d > 80 * 80 && m.etat() != Monstre.CHASSE) {
                m.discard();
            }
        }
        if (!BackroomsData.get(br.getServer()).actif()) {
            return;
        }
        int total = monstres.size();
        RandomSource r = br.getRandom();
        for (ServerPlayer j : joueurs) {
            long autour = monstres.stream().filter(m -> m.isAlive() && Math.hypot(m.getX() - j.getX(), m.getZ() - j.getZ()) < 40).count();
            if (autour >= MONSTRES_AUTOUR || total >= 10 * joueurs.size()) {
                continue;
            }
            Monstre.Sorte s = TIRAGE[r.nextInt(TIRAGE.length)];
            for (int essai = 0; essai < 30; essai++) {
                double a = r.nextDouble() * Math.PI * 2, d = 22 + r.nextDouble() * 18;
                int x = Mth.floor(j.getX() + Math.cos(a) * d), z = Mth.floor(j.getZ() + Math.sin(a) * d);
                if (!convient(s, x, z) || !br.hasChunkAt(new BlockPos(x, 1, z))) {
                    continue;
                }
                double px = x + 0.5, pz = z + 0.5;
                if (joueurs.stream().anyMatch(p -> Math.hypot(p.getX() - px, p.getZ() - pz) < 20)) {
                    continue;
                }
                Monstre m = MONSTRES.get(s).get().create(br);
                if (m == null) {
                    break;
                }
                m.moveTo(px, 1.0, pz, r.nextFloat() * 360F, 0F);
                m.setYHeadRot(m.getYRot());
                m.setYBodyRot(m.getYRot());
                br.addFreshEntity(m);
                total++;
                break;
            }
        }
    }

    // ---------------------------------------------------------------- l'hôte

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("backroom").requires(Backrooms::hote)
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    int n = envoyer(server, server.getPlayerList().getPlayers());
                    if (n < 0) {
                        ctx.getSource().sendFailure(Component.literal("La dimension des backrooms est introuvable."));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " joueur(s) s'endorment… /backroom fin pour les réveiller."), true);
                    return n;
                })
                .then(Commands.literal("fin").executes(ctx -> {
                    int n = finir(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " joueur(s) se réveillent."), true);
                    return n;
                }))
                // pour l'hôte (et les tests) : le screamer d'un monstre, sans rien d'autre
                .then(Commands.literal("screamer").then(Commands.argument("monstre", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(Monstre.Sorte.values()).map(s -> s.id), b))
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "monstre");
                            for (Monstre.Sorte s : Monstre.Sorte.values()) {
                                if (s.id.equals(id)) {
                                    ecran(ctx.getSource().getPlayerOrException(), SCREAMER_TEST, s.ordinal());
                                    return 1;
                                }
                            }
                            ctx.getSource().sendFailure(Component.literal("Monstres : rampeur, souriant, fetard, sansvisage, phalene."));
                            return 0;
                        })))
                .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                    Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                    int n = envoyer(ctx.getSource().getServer(), joueurs);
                    if (n < 0) {
                        ctx.getSource().sendFailure(Component.literal("La dimension des backrooms est introuvable."));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " joueur(s) s'endorment… /backroom fin pour les réveiller."), true);
                    return n;
                })));
    }

    /** Les commandes qui feraient sortir des backrooms sans l'hôte. */
    private static final Set<String> SORTIES = Set.of("cartel", "laboratoire", "whoop", "spawn", "home", "homes", "back", "tpa", "tpahere",
            "tpaccept", "rtp", "warp", "warps", "start", "accueil");

    @SubscribeEvent
    public static void onCommande(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer j) || !dedans(j) || hote(source)) {
            return;
        }
        String commande = event.getParseResults().getReader().getString().trim();
        if (commande.startsWith("/")) {
            commande = commande.substring(1);
        }
        String racine = commande.split(" ", 2)[0];
        if (SORTIES.contains(racine)) {
            event.setCanceled(true);
            j.displayClientMessage(Component.literal("Il n'y a pas de sortie.").withStyle(ChatFormatting.GOLD), true);
        }
    }

    // ---------------------------------------------------------------- le temps qui passe

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!ATTENTES.isEmpty()) {
            Iterator<Map.Entry<UUID, EnAttente>> it = ATTENTES.entrySet().iterator();
            List<Runnable> faire = new ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<UUID, EnAttente> e = it.next();
                ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
                if (j == null) {
                    it.remove();
                    continue;
                }
                EnAttente a = e.getValue();
                if (a.reste() > 1) {
                    e.setValue(new EnAttente(a.quoi(), a.reste() - 1));
                    continue;
                }
                it.remove();
                ServerLevel br = niveau(server);
                switch (a.quoi()) {
                    case ENTRER -> faire.add(() -> entrer(j));
                    case SORTIR -> faire.add(() -> sortir(j));
                    case SCREAMER, PIEGE, ASSOMME -> {
                        if (br != null && dedans(j)) {
                            faire.add(() -> placer(j, br));
                        }
                    }
                }
            }
            faire.forEach(Runnable::run);
        }
        ServerLevel br = niveau(server);
        if (br == null || br.players().isEmpty()) {
            return;
        }
        long t = server.getTickCount();
        if (t % 20 == 0) {
            peupler(br);
        }
        if (t % 40 == 0) {
            for (ServerPlayer j : br.players()) {
                verifierMode(j);
            }
        }
        GRACES.values().removeIf(fin -> fin < br.getGameTime());
    }

    // ---------------------------------------------------------------- rien ne se casse, rien ne se pose, on n'y meurt pas

    private static boolean ici(Level level) {
        return level != null && level.dimension() == DIMENSION;
    }

    @SubscribeEvent
    public static void onCasse(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level l && ici(l)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTape(PlayerInteractEvent.LeftClickBlock event) {
        if (ici(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPose(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level l && ici(l)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClicDroit(PlayerInteractEvent.RightClickBlock event) {
        if (ici(event.getLevel()) && (event.getItemStack().getItem() instanceof BlockItem || event.getItemStack().getItem() instanceof BucketItem)) {
            event.setUseItem(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onObjet(PlayerInteractEvent.RightClickItem event) {
        if (ici(event.getLevel()) && event.getItemStack().getItem() instanceof BucketItem) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (ici(event.getLevel())) {
            event.getAffectedBlocks().clear();
        }
    }

    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Mourir dans les backrooms, c'est s'évanouir et se réveiller ailleurs (rien ne tombe, rien ne se perd). */
    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j) || !dedans(j)) {
            return;
        }
        event.setCanceled(true);
        j.setHealth(j.getMaxHealth());
        j.clearFire();
        j.fallDistance = 0;
        j.stopRiding();
        if (!ATTENTES.containsKey(j.getUUID())) {
            ATTENTES.put(j.getUUID(), new EnAttente(Attente.ASSOMME, TEMPS_ASSOMME));
            ecran(j, ASSOMME, 0);
        }
    }

    @SubscribeEvent
    public static void onConnexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j)) {
            return;
        }
        BackroomsData data = BackroomsData.get(j.server);
        // le rêve s'est arrêté pendant qu'il n'était pas là : il se réveille chez lui
        if (!data.actif() && (dedans(j) || data.avant(j.getUUID()) != null)) {
            sortir(j);
        } else {
            verifierMode(j);
        }
    }

    @SubscribeEvent
    public static void onDeconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        // ce qui l'attendait (s'endormir, se réveiller ailleurs…) tombe ; s'il devait sortir, il sortira en revenant
        ATTENTES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            verifierMode(j);
        }
    }

    private Backrooms() {
    }
}
