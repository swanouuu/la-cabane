package fr.cabane.arene;

import com.mojang.brigadier.CommandDispatcher;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * L'arène de combat (croquis validé : docs/arene/maquette.html), dans sa propre dimension (cabane:arene).
 *
 * <p>{@code /arene} (ou l'hôte : {@code /arene envoyer <joueurs>}) amène dans la salle d'entrée, derrière la loge royale.
 * Trois portes, chacune avec son bouton : à gauche le côté bleu, à droite le côté rouge, en face les gradins (la loge). On
 * appuie sur son côté : son pseudo s'affiche sur le panneau d'attente. Tant qu'un seul côté a du monde, son panneau dit
 * « En attente d'un adversaire… ». Dès qu'il y a au moins un joueur de chaque côté, 10 secondes de compte à rebours (on
 * peut encore rejoindre), puis tous les inscrits partent dans leur base. Pendant un combat, appuyer sur un côté y envoie
 * directement ; ré-appuyer sur son bouton fait sortir de la file.
 *
 * <p>Au combat, chacun a le même kit (voir {@link Kit}) ; on garde seulement les lames de la Brume et les armes de l'éveil.
 * À 0 PV on ne meurt pas : toute l'arène voit « VICTOIRE » et le pseudo du gagnant (aussi sur l'écran géant), son côté
 * prend +1, et le perdant réapparaît 3 secondes après dans sa base avec un kit neuf, intouchable 3 secondes de plus. Le
 * bouton rouge des bases et de la loge ramène à la salle d'entrée (le stuff est rendu) ; celui de la salle ramène pile
 * là d'où l'on venait. Rien ne se casse, rien ne se pose, et rien ne touche les spectateurs.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Arene {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("arene"));

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredBlock<BoutonArene> BOUTON_BLEU = bouton("bouton_arene_bleu", BoutonArene.Role.BLEU, MapColor.COLOR_BLUE);
    public static final DeferredBlock<BoutonArene> BOUTON_ROUGE = bouton("bouton_arene_rouge", BoutonArene.Role.ROUGE, MapColor.COLOR_RED);
    public static final DeferredBlock<BoutonArene> BOUTON_GRADINS = bouton("bouton_arene_gradins", BoutonArene.Role.GRADINS, MapColor.GOLD);
    public static final DeferredBlock<BoutonArene> BOUTON_SORTIE = bouton("bouton_arene_sortie", BoutonArene.Role.SORTIE, MapColor.METAL);
    public static final DeferredBlock<BoutonArene> BOUTON_SALLE = bouton("bouton_arene_salle", BoutonArene.Role.SALLE, MapColor.METAL);

    private static DeferredBlock<BoutonArene> bouton(String nom, BoutonArene.Role role, MapColor couleur) {
        return BLOCKS.registerBlock(nom, p -> new BoutonArene(role, p),
                BlockBehaviour.Properties.of().mapColor(couleur).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 7).noLootTable());
    }

    static final String[] COTES = {"bleu", "rouge"};
    private static final String ESSAI = "cabane_arene_essai";
    private static final ChatFormatting[] COULEURS = {ChatFormatting.BLUE, ChatFormatting.RED};
    /** Le compte à rebours, une fois qu'il y a quelqu'un de chaque côté. */
    private static final int REBOURS = 200;
    /** Après un KO : 3 secondes immobile dans sa base, puis 3 secondes intouchable. */
    private static final int ATTENTE_KO = 60, PROTECTION = 60;

    /** La file de la salle d'entrée : joueur → côté (0 bleu, 1 rouge), dans l'ordre d'arrivée. */
    private static final Map<UUID, Integer> FILE = new LinkedHashMap<>();
    /** Ceux qui sont au combat : joueur → côté. */
    private static final Map<UUID, Integer> COMBATTANTS = new LinkedHashMap<>();
    /** Intouchable jusqu'à ce tick. */
    private static final Map<UUID, Long> PROTEGES = new HashMap<>();
    /** Après un KO, immobile dans sa base jusqu'à ce tick. */
    private static final Map<UUID, Long> ASSOMMES = new HashMap<>();
    private static int rebours = -1;
    private static final int[] SCORE = new int[2];
    private static long debutCombat = -1;
    private static String victoire = "";
    private static int victoireCote = 0;
    private static long finVictoire = -1;

    public static void register(IEventBus modBus) {
        for (DeferredBlock<BoutonArene> b : List.of(BOUTON_BLEU, BOUTON_ROUGE, BOUTON_GRADINS, BOUTON_SORTIE, BOUTON_SALLE)) {
            CabaneItems.ITEMS.registerSimpleBlockItem(b);
        }
        BLOCKS.register(modBus);
        modBus.addListener(Arene::paquets);
    }

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(Entity e) {
        return e.level().dimension() == DIMENSION;
    }

    public static boolean combattant(Player joueur) {
        return COMBATTANTS.containsKey(joueur.getUUID());
    }

    // ------------------------------------------------------------------ l'état envoyé aux joueurs de l'arène

    /**
     * Pour l'écran géant et les panneaux de la salle : phase (0 personne, 1 attente, 2 compte à rebours, 3 combat), secondes
     * du rebours, la file de chaque côté, le score, le chrono, les combattants (entités, côtés, PV, pseudos) et la dernière
     * victoire (pseudo, côté, ticks restants à l'afficher).
     */
    public record EtatArene(int phase, int rebours, List<String> fileBleu, List<String> fileRouge, int scoreBleu, int scoreRouge, int chrono,
            List<Integer> ids, List<Integer> cotes, List<Float> pv, List<Float> pvMax, List<String> noms, String victoire, int victoireCote,
            int victoireReste) implements CustomPacketPayload {
        public static final Type<EtatArene> TYPE = new Type<>(Cabane.id("arene_etat"));
        public static final StreamCodec<FriendlyByteBuf, EtatArene> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.phase());
            buf.writeVarInt(p.rebours());
            buf.writeCollection(p.fileBleu(), FriendlyByteBuf::writeUtf);
            buf.writeCollection(p.fileRouge(), FriendlyByteBuf::writeUtf);
            buf.writeVarInt(p.scoreBleu());
            buf.writeVarInt(p.scoreRouge());
            buf.writeVarInt(p.chrono());
            buf.writeCollection(p.ids(), FriendlyByteBuf::writeVarInt);
            buf.writeCollection(p.cotes(), FriendlyByteBuf::writeVarInt);
            buf.writeCollection(p.pv(), FriendlyByteBuf::writeFloat);
            buf.writeCollection(p.pvMax(), FriendlyByteBuf::writeFloat);
            buf.writeCollection(p.noms(), FriendlyByteBuf::writeUtf);
            buf.writeUtf(p.victoire());
            buf.writeVarInt(p.victoireCote());
            buf.writeVarInt(p.victoireReste());
        }, buf -> new EtatArene(buf.readVarInt(), buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf), buf.readList(FriendlyByteBuf::readUtf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readList(FriendlyByteBuf::readVarInt), buf.readList(FriendlyByteBuf::readVarInt),
                buf.readList(FriendlyByteBuf::readFloat), buf.readList(FriendlyByteBuf::readFloat), buf.readList(FriendlyByteBuf::readUtf), buf.readUtf(),
                buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<EtatArene> type() {
            return TYPE;
        }
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(EtatArene.TYPE, EtatArene.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.arene.ClientArene.etat(p)));
    }

    private static EtatArene etat(MinecraftServer server, long t) {
        List<String> fb = new ArrayList<>(), fr = new ArrayList<>();
        FILE.forEach((id, c) -> {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            if (j != null) {
                (c == 0 ? fb : fr).add(j.getGameProfile().getName());
            }
        });
        List<Integer> ids = new ArrayList<>(), cotes = new ArrayList<>();
        List<Float> pv = new ArrayList<>(), pvMax = new ArrayList<>();
        List<String> noms = new ArrayList<>();
        COMBATTANTS.forEach((id, c) -> {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            if (j != null) {
                ids.add(j.getId());
                cotes.add(c);
                pv.add(j.getHealth() + j.getAbsorptionAmount());
                pvMax.add(j.getMaxHealth());
                noms.add(j.getGameProfile().getName());
            }
        });
        // les mannequins d'essai (/arene essai) passent à l'écran comme des combattants
        ServerLevel l = niveau(server);
        if (l != null) {
            for (Entity e : l.getAllEntities()) {
                if (e instanceof LivingEntity le && e.getTags().contains(ESSAI)) {
                    ids.add(e.getId());
                    cotes.add(e.getTags().contains(ESSAI + "_rouge") ? 1 : 0);
                    pv.add(le.getHealth());
                    pvMax.add(le.getMaxHealth());
                    noms.add(e.getTags().contains(ESSAI + "_rouge") ? "Mannequin rouge" : "Mannequin bleu");
                }
            }
        }
        int phase = !ids.isEmpty() ? 3 : rebours >= 0 ? 2 : !FILE.isEmpty() ? 1 : 0;
        int chrono = debutCombat < 0 ? 0 : (int) ((t - debutCombat) / 20);
        int reste = finVictoire < 0 ? 0 : (int) Math.max(0, finVictoire - t);
        return new EtatArene(phase, rebours < 0 ? 0 : (rebours + 19) / 20, fb, fr, SCORE[0], SCORE[1], chrono, ids, cotes, pv, pvMax, noms, victoire,
                victoireCote, reste);
    }

    // ------------------------------------------------------------------ entrer, sortir

    /** Le chantier en cours (null : rien à construire), et ceux qui attendent qu'il finisse pour entrer. */
    private static AreneBuilder.Chantier chantier;
    private static final java.util.Set<UUID> ATTENTE_CHANTIER = new java.util.LinkedHashSet<>();

    /** Lance la construction (étalée sur quelques secondes, le serveur ne gèle pas). */
    static void construire(ServerLevel l) {
        if (chantier == null) {
            chantier = new AreneBuilder.Chantier(l);
        }
    }

    /** Un peu du chantier à chaque tick ; à la fin, ceux qui attendaient entrent. */
    private static void chantier(MinecraftServer server, ServerLevel l, long t) {
        if (chantier == null) {
            return;
        }
        if (t % 20 == 0) {
            int pc = chantier.pourcent();
            for (UUID id : ATTENTE_CHANTIER) {
                ServerPlayer j = server.getPlayerList().getPlayer(id);
                if (j != null) {
                    j.displayClientMessage(Component.literal("L'arène se construit… " + pc + " %").withStyle(ChatFormatting.GOLD), true);
                }
            }
        }
        if (!chantier.avancer(l)) {
            return;
        }
        chantier = null;
        AreneData.get(server).setVersionConstruite(AreneBuilder.VERSION);
        for (UUID id : List.copyOf(ATTENTE_CHANTIER)) {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            if (j != null) {
                entrer(j);
            }
        }
        ATTENTE_CHANTIER.clear();
    }

    /** Emmène le joueur à la salle d'entrée (l'arène se construit d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel arene = niveau(server);
        if (arene == null) {
            joueur.sendSystemMessage(Component.literal("L'arène n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        AreneData data = AreneData.get(server);
        if (data.versionConstruite() != AreneBuilder.VERSION || chantier != null) {
            // première visite (ou après une mise à jour) : l'arène se construit, il entre dès que c'est fini
            construire(arene);
            ATTENTE_CHANTIER.add(joueur.getUUID());
            joueur.displayClientMessage(Component.literal("L'arène se construit… " + chantier.pourcent() + " %").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        Vec3 a = AreneBuilder.arriveeSalle();
        if (dedans(joueur)) {
            versSalle(joueur);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new AreneData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1F);
        depart.sendParticles(ParticleTypes.CLOUD, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.3, 0.8, 0.3, 0.02);
        joueur.teleportTo(arene, a.x, a.y, a.z, AreneBuilder.SALLE_YAW, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        arene.playSound(null, a.x, a.y, a.z, SoundEvents.BELL_BLOCK, SoundSource.AMBIENT, 0.8F, 0.8F);
        titre(joueur, Component.literal("L'ARÈNE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Porte bleue à gauche, rouge à droite, gradins en face").withStyle(ChatFormatting.GRAY), 50);
        joueur.sendSystemMessage(Component.literal("Appuie sur le bouton de ton côté pour combattre, ou sur le bouton doré pour aller dans les gradins. "
                + "Le bouton rouge, près de l'arrivée, te ramène là où tu étais.").withStyle(ChatFormatting.GRAY));
    }

    /** Dans l'arène, retour à la salle d'entrée (un combattant récupère son stuff). */
    static void versSalle(ServerPlayer joueur) {
        if (COMBATTANTS.containsKey(joueur.getUUID())) {
            quitterCombat(joueur);
        }
        Vec3 a = AreneBuilder.arriveeSalle();
        joueur.teleportTo(niveau(joueur.server), a.x, a.y, a.z, AreneBuilder.SALLE_YAW, 0F);
        joueur.fallDistance = 0;
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas à l'arène.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (COMBATTANTS.containsKey(joueur.getUUID())) {
            quitterCombat(joueur);
        }
        FILE.remove(joueur.getUUID());
        MinecraftServer server = joueur.server;
        AreneData data = AreneData.get(server);
        AreneData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1F, 1.4F);
        joueur.clearFire();
        if (cible != null && cible != ici) {
            joueur.teleportTo(cible, retour.x(), retour.y(), retour.z(), retour.yaw(), retour.pitch());
        } else {
            ServerLevel monde = server.getLevel(joueur.getRespawnDimension());
            BlockPos p = joueur.getRespawnPosition();
            if (monde == null || p == null || monde == ici) {
                monde = server.overworld();
                p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            } else {
                p = p.above();
            }
            joueur.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, joueur.getYRot(), 0F);
        }
        data.setRetour(joueur.getUUID(), null);
        verifierMode(joueur);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
    }

    static void titre(ServerPlayer joueur, Component titre, Component sousTitre, int duree) {
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(5, duree, 15));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(sousTitre));
        joueur.connection.send(new ClientboundSetTitleTextPacket(titre));
    }

    /** À l'arène, un joueur en survie passe en aventure (on n'y casse rien) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer joueur) {
        AreneData data = AreneData.get(joueur.server);
        GameType avant = data.mode(joueur.getUUID());
        if (dedans(joueur)) {
            if (joueur.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                data.setMode(joueur.getUUID(), GameType.SURVIVAL);
                joueur.setGameMode(GameType.ADVENTURE);
            }
        } else if (avant != null) {
            data.setMode(joueur.getUUID(), null);
            if (joueur.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                joueur.setGameMode(avant);
            }
        }
    }

    // ------------------------------------------------------------------ les boutons, la file, le combat

    static void bouton(ServerPlayer joueur, BoutonArene.Role role) {
        switch (role) {
            case SORTIE -> sortir(joueur);
            case SALLE -> versSalle(joueur);
            case GRADINS -> {
                FILE.remove(joueur.getUUID());
                Vec3 l = AreneBuilder.loge();
                joueur.teleportTo(niveau(joueur.server), l.x, l.y, l.z, AreneBuilder.LOGE_YAW, 10F);
                joueur.serverLevel().playSound(null, l.x, l.y, l.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.3F);
                joueur.displayClientMessage(Component.literal("La loge royale. Le bouton rouge te ramène à la salle d'entrée.").withStyle(ChatFormatting.GOLD), true);
            }
            case BLEU, ROUGE -> choisirCote(joueur, role == BoutonArene.Role.BLEU ? 0 : 1);
        }
    }

    private static void choisirCote(ServerPlayer joueur, int cote) {
        UUID id = joueur.getUUID();
        if (COMBATTANTS.containsKey(id)) {
            return;
        }
        Integer deja = FILE.get(id);
        if (deja != null && deja == cote) {
            FILE.remove(id);
            joueur.displayClientMessage(Component.literal("Tu sors de la file.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!COMBATTANTS.isEmpty()) {
            FILE.remove(id);
            envoyerCombat(joueur, cote);
            return;
        }
        FILE.put(id, cote);
        joueur.displayClientMessage(Component.literal("Tu es côté " + COTES[cote] + ". Attends sur le tapis ; ré-appuie pour sortir de la file.")
                .withStyle(COULEURS[cote]), true);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8F, cote == 0 ? 0.9F : 1.2F);
    }

    /** Au combat : le stuff est rangé, le kit donné, direction sa base. */
    private static void envoyerCombat(ServerPlayer joueur, int cote) {
        long t = joueur.server.getTickCount();
        if (COMBATTANTS.isEmpty()) {
            debutCombat = t;
            SCORE[0] = SCORE[1] = 0;
            finVictoire = -1;
        }
        Kit.ranger(joueur);
        Kit.donner(joueur);
        COMBATTANTS.put(joueur.getUUID(), cote);
        remettreEnForme(joueur);
        Vec3 b = AreneBuilder.base(cote);
        joueur.teleportTo(niveau(joueur.server), b.x, b.y, b.z, AreneBuilder.baseYaw(cote), 0F);
        PROTEGES.put(joueur.getUUID(), t + PROTECTION);
        titre(joueur, Component.literal("AU COMBAT").withStyle(COULEURS[cote], ChatFormatting.BOLD),
                Component.literal("Côté " + COTES[cote] + " · le bouton rouge de ta base te ramène à la salle").withStyle(ChatFormatting.GRAY), 40);
        joueur.serverLevel().playSound(null, b.x, b.y, b.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1F, 1F);
    }

    /** Hors du combat : le kit disparaît, le stuff revient. */
    private static void quitterCombat(ServerPlayer joueur) {
        COMBATTANTS.remove(joueur.getUUID());
        PROTEGES.remove(joueur.getUUID());
        ASSOMMES.remove(joueur.getUUID());
        Kit.rendre(joueur);
        remettreEnForme(joueur);
    }

    private static void remettreEnForme(ServerPlayer joueur) {
        joueur.setHealth(joueur.getMaxHealth());
        joueur.getFoodData().setFoodLevel(20);
        joueur.getFoodData().setSaturation(5F);
        joueur.clearFire();
        joueur.setTicksFrozen(0);
        joueur.fallDistance = 0;
        for (MobEffectInstance e : List.copyOf(joueur.getActiveEffects())) {
            if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                joueur.removeEffect(e.getEffect());
            }
        }
    }

    /** Un combattant tombe à 0 : VICTOIRE pour l'autre, +1 à son côté, et le perdant repart de sa base 3 secondes après. */
    private static void ko(ServerPlayer perdant, DamageSource source) {
        MinecraftServer server = perdant.server;
        long t = server.getTickCount();
        int cotePerdant = COMBATTANTS.get(perdant.getUUID());
        int coteGagnant = 1 - cotePerdant;
        ServerPlayer gagnant = null;
        Entity e = source.getEntity();
        if (e instanceof ServerPlayer j && COMBATTANTS.containsKey(j.getUUID()) && COMBATTANTS.get(j.getUUID()) != cotePerdant) {
            gagnant = j;
        } else if (perdant.getKillCredit() instanceof ServerPlayer j && COMBATTANTS.containsKey(j.getUUID()) && COMBATTANTS.get(j.getUUID()) != cotePerdant) {
            gagnant = j;
        }
        SCORE[coteGagnant]++;
        victoire = gagnant != null ? gagnant.getGameProfile().getName() : "Côté " + COTES[coteGagnant];
        victoireCote = coteGagnant;
        finVictoire = t + 70;
        ServerLevel l = niveau(server);
        Component titreV = Component.literal("VICTOIRE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component sous = Component.literal(victoire).withStyle(COULEURS[coteGagnant], ChatFormatting.BOLD)
                .append(Component.literal("  " + SCORE[0] + " – " + SCORE[1]).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer j : l.players()) {
            titre(j, titreV, sous, 50);
            j.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.5F, 1F);
        }
        l.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, perdant.getX(), perdant.getY() + 1, perdant.getZ(), 40, 0.4, 0.8, 0.4, 0.4);
        // le perdant : remis sur pied, kit neuf, immobile 3 s dans sa base, puis 3 s intouchable
        perdant.stopRiding();
        remettreEnForme(perdant);
        Kit.donner(perdant);
        Vec3 b = AreneBuilder.base(cotePerdant);
        perdant.teleportTo(l, b.x, b.y, b.z, AreneBuilder.baseYaw(cotePerdant), 0F);
        perdant.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false));
        ASSOMMES.put(perdant.getUUID(), t + ATTENTE_KO);
        PROTEGES.put(perdant.getUUID(), t + ATTENTE_KO + PROTECTION);
    }

    // ------------------------------------------------------------------ commandes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("arene")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("salle").executes(ctx -> {
                    ServerPlayer j = ctx.getSource().getPlayerOrException();
                    if (dedans(j)) {
                        versSalle(j);
                    } else {
                        entrer(j);
                    }
                    return 1;
                }))
                .then(Commands.literal("envoyer").requires(Arene::hote)
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                            Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                            joueurs.forEach(Arene::entrer);
                            ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) à l'arène."), true);
                            return joueurs.size();
                        })))
                .then(Commands.literal("cote").then(Commands.literal("bleu").executes(ctx -> {
                    choisirCote(ctx.getSource().getPlayerOrException(), 0);
                    return 1;
                })).then(Commands.literal("rouge").executes(ctx -> {
                    choisirCote(ctx.getSource().getPlayerOrException(), 1);
                    return 1;
                })))
                .then(Commands.literal("essai").requires(Arene::hote).then(Commands.literal("combat").executes(ctx -> {
                    // l'hôte va seul au combat côté bleu (pour essayer le kit, la base et le KO sans adversaire)
                    ServerPlayer j = ctx.getSource().getPlayerOrException();
                    if (dedans(j) && !COMBATTANTS.containsKey(j.getUUID())) {
                        FILE.remove(j.getUUID());
                        envoyerCombat(j, 0);
                    }
                    return 1;
                })).executes(ctx -> {
                    // deux mannequins de la salle d'entraînement face à face sur le sable (ou on les enlève), pour voir l'écran
                    ServerLevel l = niveau(ctx.getSource().getServer());
                    List<Entity> la = new ArrayList<>();
                    l.getAllEntities().forEach(e -> {
                        if (e.getTags().contains(ESSAI)) {
                            la.add(e);
                        }
                    });
                    if (!la.isEmpty()) {
                        la.forEach(Entity::discard);
                        ctx.getSource().sendSuccess(() -> Component.literal("Mannequins d'essai enlevés."), false);
                        return 0;
                    }
                    for (int cote = 0; cote < 2; cote++) {
                        var m = fr.cabane.entrainement.Entrainement.PNJ.get().create(l);
                        if (m == null) {
                            return 0;
                        }
                        m.preparer(new Vec3(cote == 0 ? -1.6 : 1.6, AreneBuilder.SOL + 1, 0.5), cote == 0 ? -90F : 90F, false);
                        m.addTag(ESSAI);
                        m.addTag(ESSAI + (cote == 0 ? "_bleu" : "_rouge"));
                        l.addFreshEntity(m);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("Deux mannequins d'essai sur l'estrade du milieu."), false);
                    return 1;
                }))
                .then(Commands.literal("reconstruire").requires(Arene::hote).executes(ctx -> {
                    ServerLevel l = niveau(ctx.getSource().getServer());
                    if (l == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension de l'arène est introuvable."));
                        return 0;
                    }
                    construire(l);
                    ctx.getSource().sendSuccess(() -> Component.literal("L'arène se reconstruit (quelques secondes)."), true);
                    return 1;
                })));
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    // ------------------------------------------------------------------ à chaque tick

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel l = niveau(server);
        if (l == null) {
            return;
        }
        long t = server.getTickCount();
        chantier(server, l, t);
        // la file : seulement ceux qui attendent dans la salle d'entrée
        FILE.keySet().removeIf(id -> {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            return j == null || !dedans(j) || !AreneBuilder.dansSalle(j.getX(), j.getZ());
        });
        COMBATTANTS.keySet().removeIf(id -> {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            return j == null || !dedans(j);
        });
        if (COMBATTANTS.isEmpty() && debutCombat >= 0) {
            debutCombat = -1;
        }
        compteARebours(server, l, t);
        for (ServerPlayer j : List.copyOf(l.players())) {
            surveiller(j, l, t);
        }
        PROTEGES.values().removeIf(fin -> fin < t);
        if (t % 5 == 0 && !l.players().isEmpty()) {
            EtatArene e = etat(server, t);
            for (ServerPlayer j : l.players()) {
                PacketDistributor.sendToPlayer(j, e);
            }
        }
        if (t % 200 == 0 && !l.players().isEmpty() && AreneData.get(server).versionConstruite() == AreneBuilder.VERSION) {
            AreneBuilder.reparer(l);
        }
    }

    private static void compteARebours(MinecraftServer server, ServerLevel l, long t) {
        boolean bleu = FILE.containsValue(0), rouge = FILE.containsValue(1);
        if (!COMBATTANTS.isEmpty() || !bleu || !rouge) {
            rebours = -1;
            if (!COMBATTANTS.isEmpty() && !FILE.isEmpty()) {
                // un combat a commencé pendant qu'ils attendaient : ils y vont
                for (Map.Entry<UUID, Integer> e : List.copyOf(FILE.entrySet())) {
                    ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
                    FILE.remove(e.getKey());
                    if (j != null) {
                        envoyerCombat(j, e.getValue());
                    }
                }
            }
            return;
        }
        if (rebours < 0) {
            rebours = REBOURS;
        }
        if (rebours % 20 == 0) {
            int s = rebours / 20;
            for (UUID id : FILE.keySet()) {
                ServerPlayer j = server.getPlayerList().getPlayer(id);
                if (j != null) {
                    j.displayClientMessage(Component.literal("Combat dans " + s + "…").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
                    if (s <= 5 && s > 0) {
                        j.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.7F, 0.8F + (5 - s) * 0.1F);
                    }
                }
            }
        }
        if (rebours-- > 0) {
            return;
        }
        rebours = -1;
        for (Map.Entry<UUID, Integer> e : List.copyOf(FILE.entrySet())) {
            ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
            if (j != null) {
                envoyerCombat(j, e.getValue());
            }
        }
        FILE.clear();
    }

    /** Chacun reste à sa place : les combattants dans l'arène, les autres hors du sol de l'arène, personne dans le vide. */
    private static void surveiller(ServerPlayer j, ServerLevel l, long t) {
        if (t % 40 == 0) {
            verifierMode(j);
        }
        boolean libre = j.isCreative() || j.isSpectator();
        if (j.getY() < AreneBuilder.SOL - 20 && !libre) {
            versSalle(j);
            return;
        }
        UUID id = j.getUUID();
        Integer cote = COMBATTANTS.get(id);
        if (cote != null) {
            Long assomme = ASSOMMES.get(id);
            if (assomme != null) {
                Vec3 b = AreneBuilder.base(cote);
                if (t >= assomme) {
                    ASSOMMES.remove(id);
                    j.displayClientMessage(Component.literal("Au combat !").withStyle(COULEURS[cote], ChatFormatting.BOLD), true);
                    j.playNotifySound(SoundEvents.BELL_BLOCK, SoundSource.MASTER, 0.8F, 1.2F);
                } else {
                    if (j.position().distanceToSqr(b) > 0.6) {
                        j.teleportTo(b.x, b.y, b.z);
                    }
                    j.setDeltaMovement(Vec3.ZERO);
                    j.displayClientMessage(Component.literal("Retour au combat dans " + ((assomme - t + 19) / 20) + "…").withStyle(ChatFormatting.GRAY), true);
                }
            }
            double d = AreneBuilder.ecart(j.getX(), j.getZ());
            if (d > -0.4) {
                Vec3 p = AreneBuilder.rentrer(j.getX(), j.getY(), j.getZ(), 1.5);
                j.teleportTo(p.x, p.y, p.z);
            }
            if (j.getY() > AreneBuilder.SOL + 38) {
                j.teleportTo(j.getX(), AreneBuilder.SOL + 37, j.getZ());
            }
            if (t % 20 == 0 && COMBATTANTS.size() > 0 && !COMBATTANTS.containsValue(1 - cote) && assomme == null) {
                j.displayClientMessage(Component.literal("En attente d'un adversaire…").withStyle(ChatFormatting.YELLOW), true);
            }
        } else if (!libre && AreneBuilder.ecart(j.getX(), j.getZ()) < 0.6 && j.getY() < AreneBuilder.SOL + 40) {
            // un spectateur descendu sur le sol de l'arène : retour à la loge
            Vec3 lo = AreneBuilder.loge();
            j.teleportTo(lo.x, lo.y, lo.z);
            j.displayClientMessage(Component.literal("Pour combattre, passe par la salle d'entrée.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    // ------------------------------------------------------------------ les règles

    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j) || !dedans(j)) {
            return;
        }
        event.setCanceled(true);
        j.setHealth(j.getMaxHealth());
        if (COMBATTANTS.containsKey(j.getUUID())) {
            ko(j, event.getSource());
        } else {
            remettreEnForme(j);
            versSalle(j);
        }
    }

    /**
     * Rien ne touche les spectateurs ni ceux de la salle ; un combattant protégé (sortie de base) est intouchable ; pas de
     * coups entre joueurs d'un même côté ; les braseros ne brûlent pas.
     */
    @SubscribeEvent
    public static void onBlessure(LivingIncomingDamageEvent event) {
        LivingEntity cible = event.getEntity();
        if (!(cible instanceof ServerPlayer j) || !dedans(j)) {
            return;
        }
        DamageSource s = event.getSource();
        if (s.is(DamageTypes.CAMPFIRE) || s.is(DamageTypes.GENERIC_KILL)) {
            if (s.is(DamageTypes.CAMPFIRE)) {
                event.setCanceled(true);
            }
            return;
        }
        Integer cote = COMBATTANTS.get(j.getUUID());
        if (cote == null || PROTEGES.containsKey(j.getUUID())) {
            event.setCanceled(true);
            return;
        }
        if (s.getEntity() instanceof ServerPlayer a && a != j) {
            Integer coteA = COMBATTANTS.get(a.getUUID());
            if (coteA == null || coteA.equals(cote) || ASSOMMES.containsKey(a.getUUID())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onFrappe(AttackEntityEvent event) {
        Player a = event.getEntity();
        if (!dedans(a) || a.isCreative()) {
            return;
        }
        Integer coteA = COMBATTANTS.get(a.getUUID());
        if (coteA == null || ASSOMMES.containsKey(a.getUUID())) {
            event.setCanceled(true);
            return;
        }
        if (event.getTarget() instanceof Player c) {
            Integer coteC = COMBATTANTS.get(c.getUUID());
            if (coteC == null || coteC.equals(coteA)) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean ici(LevelAccessor l) {
        return l instanceof Level lv && lv.dimension() == DIMENSION;
    }

    @SubscribeEvent
    public static void onCasse(BlockEvent.BreakEvent event) {
        if (ici(event.getLevel()) && !event.getPlayer().isCreative()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPose(BlockEvent.EntityPlaceEvent event) {
        if (ici(event.getLevel()) && !(event.getEntity() instanceof Player p && p.isCreative())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (ici(event.getLevel())) {
            event.getAffectedBlocks().clear();
        }
    }

    /** On ne touche à rien (tonneaux, braseros…) sauf aux boutons de l'arène. */
    @SubscribeEvent
    public static void onClicDroit(PlayerInteractEvent.RightClickBlock event) {
        if (!ici(event.getLevel()) || event.getEntity().isCreative()) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BoutonArene)) {
            event.setUseBlock(TriState.FALSE);
        }
    }

    /** Le kit ne se jette pas. */
    @SubscribeEvent
    public static void onJette(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer j && dedans(j) && Kit.duKit(event.getEntity().getItem())) {
            ItemStack s = event.getEntity().getItem().copy();
            event.setCanceled(true);
            if (!j.getInventory().add(s)) {
                j.drop(s, false);
            }
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur)) {
            return;
        }
        if (event.getFrom() == DIMENSION && event.getTo() != DIMENSION) {
            FILE.remove(joueur.getUUID());
            if (COMBATTANTS.containsKey(joueur.getUUID()) || AreneData.get(joueur.server).rangement(joueur.getUUID()) != null) {
                quitterCombat(joueur);
            }
        }
        verifierMode(joueur);
    }

    /** Revenu après avoir quitté en plein combat : stuff rendu, et retour à la salle d'entrée s'il est resté sur le sol de l'arène. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur)) {
            return;
        }
        if (!COMBATTANTS.containsKey(joueur.getUUID()) && AreneData.get(joueur.server).rangement(joueur.getUUID()) != null) {
            Kit.rendre(joueur);
        }
        if (dedans(joueur) && AreneBuilder.ecart(joueur.getX(), joueur.getZ()) < 0.6 && joueur.getY() < AreneBuilder.SOL + 40) {
            versSalle(joueur);
        }
        verifierMode(joueur);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur)) {
            return;
        }
        FILE.remove(joueur.getUUID());
        ATTENTE_CHANTIER.remove(joueur.getUUID());
        if (COMBATTANTS.containsKey(joueur.getUUID())) {
            quitterCombat(joueur);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Aucune créature n'apparaît d'elle-même à l'arène. */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    private Arene() {
    }
}
