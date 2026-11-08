package fr.cabane.histoire;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import fr.cabane.hub.HubBuilder;
import fr.cabane.hub.HubData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * /raid : le grand raid des Pillards du Sud. Plusieurs vagues (trois par défaut) entrent par la grande porte du sud et
 * les deux passages du sud de la palissade, de plus en plus nombreuses et mieux armées ; la dernière amène leur chef.
 * Une barre en haut de l'écran compte ceux qui restent. Chaque vague repoussée rapporte de l'argent à tous.
 * <p>
 * Les bâtiments du village ne craignent rien (voir hub/Protection) : les Pillards en ont après les joueurs.
 * <p>
 * /raid allume aussi les raids automatiques : après chaque raid repoussé, le suivant arrive au hasard entre 30 minutes et
 * 1 h 30 plus tard (une minute avant, on entend les tambours), jusqu'à /raid stop (ou /stopraid). Personne en ligne ou
 * monde en paisible : il attend cinq minutes et réessaie. /raid info dit quand arrive le prochain.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class GrandRaid {
    public static final String TAG = "cabane_grand_raid";
    private static final int PAUSE_DEBUT = 20 * 8;
    private static final int PAUSE_VAGUES = 20 * 15;
    private static final long PRIME_PAR_VAGUE = 400;
    /** Les raids automatiques : entre deux raids, de 30 minutes à 1 h 30 ; le préavis ; l'attente quand ce n'est pas possible. */
    private static final int ATTENTE_MIN = 20 * 60 * 30, ATTENTE_MAX = 20 * 60 * 90;
    private static final int PREAVIS = 20 * 60;
    private static final int REESSAI = 20 * 60 * 5;
    /** prochainRaid vaut ça pendant un raid automatique : le suivant se tire au sort quand celui-ci finit. */
    private static final long APRES_CE_RAID = -1;
    private static boolean prevenu;

    private static int vagues;
    private static int vague;
    private static int pause;
    private static int total;
    private static int dureeVague;
    /** Les pillards de la vague en cours, et où on les a vus pour la dernière fois (s'ils sont dans un chunk déchargé). */
    private static final Map<UUID, BlockPos> PILLARDS = new HashMap<>();
    private static ServerBossEvent barre;
    private static MinecraftServer serveur;

    // ================================================================ la commande

    @SubscribeEvent
    public static void onCommandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("raid").requires(GrandRaid::hote)
                .executes(ctx -> lancer(ctx.getSource(), 3))
                .then(Commands.argument("vagues", IntegerArgumentType.integer(1, 5))
                        .executes(ctx -> lancer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "vagues"))))
                .then(Commands.literal("stop").executes(ctx -> arreter(ctx.getSource())))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource()))));
        // « /stop raid » arrêterait le serveur (/stop est la commande de Minecraft) : /stopraid fait comme /raid stop
        event.getDispatcher().register(Commands.literal("stopraid").requires(GrandRaid::hote).executes(ctx -> arreter(ctx.getSource())));
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    /** /raid : un raid tout de suite, et les raids automatiques allumés (avec ce nombre de vagues). */
    private static int lancer(CommandSourceStack source, int nombre) {
        MinecraftServer server = source.getServer();
        String refus = demarrer(server, nombre);
        if (refus != null) {
            source.sendFailure(Component.literal(refus));
            return 0;
        }
        HubData data = HubData.get(server);
        data.setRaidsAuto(true, nombre);
        data.setProchainRaid(APRES_CE_RAID);
        source.sendSuccess(() -> Component.literal("Grand raid lancé : " + nombre + (nombre > 1 ? " vagues." : " vague.")
                + " Les raids automatiques sont allumés : un nouveau raid toutes les 30 min à 1 h 30, jusqu'à /raid stop."), true);
        return nombre;
    }

    /** /raid info : y a-t-il des raids automatiques, et quand arrive le prochain ? */
    private static int info(CommandSourceStack source) {
        HubData data = HubData.get(source.getServer());
        String texte;
        if (!data.raidsAuto()) {
            texte = "Raids automatiques : coupés. /raid pour les allumer.";
        } else if (vagues > 0 || data.prochainRaid() == APRES_CE_RAID) {
            texte = "Raids automatiques : allumés. Un raid est en cours ; le suivant se tirera au sort quand il sera repoussé.";
        } else {
            long reste = Math.max(0, data.prochainRaid() - source.getServer().overworld().getGameTime());
            texte = "Raids automatiques : allumés (" + data.vaguesAuto() + " vagues). Prochain raid dans environ " + Math.max(1, reste / 1200) + " min.";
        }
        source.sendSuccess(() -> Component.literal(texte).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    /** Lance un grand raid ; renvoie pourquoi c'est impossible, ou null s'il part. */
    private static String demarrer(MinecraftServer server, int nombre) {
        if (HubData.get(server).centre() == null) {
            return "Beauvais n'est pas encore construit.";
        }
        if (vagues > 0) {
            return "Un raid est déjà en cours (vague " + vague + "/" + vagues + "). /raid stop pour l'arrêter.";
        }
        if (server.overworld().getDifficulty() == Difficulty.PEACEFUL) {
            return "Le monde est en paisible : les Pillards disparaîtraient tout de suite. Passe en facile ou plus (/difficulty).";
        }
        prevenu = false;
        vagues = nombre;
        vague = 0;
        pause = PAUSE_DEBUT;
        PILLARDS.clear();
        serveur = server;
        charger(server.overworld(), HubData.get(server).centre(), true);
        barre = new ServerBossEvent(Component.literal("Les Pillards du Sud approchent…").withStyle(ChatFormatting.DARK_RED),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        barre.setDarkenScreen(true);
        barre.setProgress(1F);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            barre.addPlayer(p);
            titre(p, "⚔ LES PILLARDS DU SUD ⚔", "ils arrivent par le sud… " + nombre + (nombre > 1 ? " vagues" : " vague"));
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3F, 0.9F);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ Le cor des Pillards résonne dans la jungle : un grand raid marche sur Beauvais ! "
                + "Défendez le village (grande porte du sud et passages sud de la palissade).").withStyle(ChatFormatting.RED), false);
        return null;
    }

    /** /raid stop (ou /stopraid) : le raid en cours s'arrête, et il n'y en a plus d'automatiques. */
    private static int arreter(CommandSourceStack source) {
        HubData data = HubData.get(source.getServer());
        boolean auto = data.raidsAuto();
        data.setRaidsAuto(false, data.vaguesAuto());
        prevenu = false;
        if (vagues == 0) {
            if (auto) {
                source.sendSuccess(() -> Component.literal("Raids automatiques coupés : plus de raid tant que tu ne refais pas /raid."), true);
                return 1;
            }
            source.sendFailure(Component.literal("Aucun raid en cours, et les raids automatiques sont déjà coupés."));
            return 0;
        }
        int n = nettoyer(source.getServer());
        finir();
        source.sendSuccess(() -> Component.literal("Raid arrêté : " + n + " pillards renvoyés dans leurs marais."
                + (auto ? " Raids automatiques coupés." : "")), true);
        return n;
    }

    /** Les raids automatiques, une fois par seconde quand aucun raid n'est en cours : le préavis, puis le raid. */
    private static void automatique(MinecraftServer server) {
        HubData data = HubData.get(server);
        if (!data.raidsAuto()) {
            return;
        }
        long maintenant = server.overworld().getGameTime();
        if (data.prochainRaid() == APRES_CE_RAID || data.prochainRaid() <= 0) {
            // un raid coupé par un redémarrage, ou jamais tiré au sort : on tire le suivant
            planifier(server);
            return;
        }
        boolean personne = server.getPlayerList().getPlayers().stream().noneMatch(p -> p.level() == server.overworld());
        if (maintenant >= data.prochainRaid() - PREAVIS && !prevenu && !personne) {
            prevenu = true;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2F, 0.6F);
            }
            server.getPlayerList().broadcastSystemMessage(Component.literal("🥁 Des tambours résonnent au sud de la jungle… "
                    + "les Pillards du Sud attaqueront Beauvais dans une minute !").withStyle(ChatFormatting.GOLD), false);
        }
        if (maintenant < data.prochainRaid()) {
            return;
        }
        // personne pour défendre, ou monde en paisible : on réessaie dans cinq minutes
        if (personne || server.overworld().getDifficulty() == Difficulty.PEACEFUL || demarrer(server, data.vaguesAuto()) != null) {
            prevenu = false;
            data.setProchainRaid(maintenant + REESSAI);
            return;
        }
        data.setProchainRaid(APRES_CE_RAID);
    }

    /** Le prochain raid automatique : entre 30 minutes et 1 h 30 à partir de maintenant. */
    private static void planifier(MinecraftServer server) {
        ServerLevel level = server.overworld();
        prevenu = false;
        HubData.get(server).setProchainRaid(level.getGameTime() + level.random.nextIntBetweenInclusive(ATTENTE_MIN, ATTENTE_MAX));
    }

    private static int nettoyer(MinecraftServer server) {
        List<Entity> partants = new ArrayList<>();
        for (Entity e : server.overworld().getAllEntities()) {
            if (e.getTags().contains(TAG)) {
                partants.add(e);
            }
        }
        partants.forEach(Entity::discard);
        return partants.size();
    }

    /** Pendant le raid, le village et ses entrées restent chargés : les pillards marchent même si personne n'est à la porte. */
    private static BlockPos charge;

    private static void charger(ServerLevel level, BlockPos centre, boolean forcer) {
        int r = (HubBuilder.rayon() + 20) >> 4;
        for (int cx = (centre.getX() >> 4) - r; cx <= (centre.getX() >> 4) + r; cx++) {
            for (int cz = (centre.getZ() >> 4) - r; cz <= (centre.getZ() >> 4) + r; cz++) {
                level.setChunkForced(cx, cz, forcer);
            }
        }
        charge = forcer ? centre : null;
    }

    private static void finir() {
        if (charge != null && serveur != null) {
            charger(serveur.overworld(), charge, false);
        }
        vagues = 0;
        vague = 0;
        PILLARDS.clear();
        if (barre != null) {
            barre.removeAllPlayers();
            barre = null;
        }
    }

    // ================================================================ le déroulé

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (vagues == 0) {
            if (event.getServer().getTickCount() % 20 == 0) {
                automatique(event.getServer());
            }
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        BlockPos centre = HubData.get(server).centre();
        if (centre == null) {
            finir();
            return;
        }
        if (server.getTickCount() % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level() == level) {
                    barre.addPlayer(p);
                } else {
                    barre.removePlayer(p);
                }
            }
        }
        if (pause > 0) {
            if (--pause == 0) {
                vague++;
                envoyer(level, centre);
            }
            return;
        }
        dureeVague++;
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        // qui reste : un pillard introuvable dans un chunk chargé est mort (ou parti)
        for (Iterator<Map.Entry<UUID, BlockPos>> it = PILLARDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BlockPos> e = it.next();
            Entity p = level.getEntity(e.getKey());
            if (p != null && p.isAlive()) {
                e.setValue(p.blockPosition());
                if (p instanceof Mob mob && server.getTickCount() % 60 == 0) {
                    guider(level, centre, mob);
                }
                // au bout de quatre minutes, les derniers brillent pour qu'on les trouve
                if (dureeVague > 20 * 240 && p instanceof Mob mob && !mob.hasEffect(MobEffects.GLOWING)) {
                    mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 600, 0, false, false));
                }
            } else if (p != null || level.isLoaded(e.getValue())) {
                it.remove();
            }
        }
        int restants = PILLARDS.size();
        barre.setName(Component.literal("Les Pillards du Sud — vague " + vague + "/" + vagues + " — " + restants + " restants")
                .withStyle(ChatFormatting.DARK_RED));
        barre.setProgress(total == 0 ? 0F : Math.min(1F, restants / (float) total));
        if (restants > 0) {
            return;
        }
        long prime = PRIME_PAR_VAGUE * vague;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == level && p.distanceToSqr(Vec3.atCenterOf(centre)) < 200 * 200) {
                Economie.crediter(server, p.getUUID(), prime);
            }
        }
        if (vague >= vagues) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                titre(p, "VICTOIRE !", "Beauvais a repoussé les Pillards du Sud");
                p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            }
            server.getPlayerList().broadcastSystemMessage(Component.literal("★ Les Pillards du Sud fuient vers leurs marais ! Beauvais a tenu bon. "
                    + "Prime de la dernière vague : ").append(Economie.texte(prime)).append(" pour chaque défenseur.").withStyle(ChatFormatting.GOLD), false);
            finir();
            // les raids automatiques : le prochain se tire au sort maintenant
            if (HubData.get(server).raidsAuto()) {
                planifier(server);
            }
            return;
        }
        pause = PAUSE_VAGUES;
        barre.setName(Component.literal("Vague " + vague + " repoussée ! La suivante arrive…").withStyle(ChatFormatting.GOLD));
        barre.setProgress(1F);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            titre(p, "Vague " + vague + " repoussée !", "Soignez-vous : la vague " + (vague + 1) + " arrive");
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ Vague " + vague + " repoussée ! Prime : ").append(Economie.texte(prime))
                .append(" pour chaque défenseur. La vague " + (vague + 1) + " arrive dans 15 secondes…").withStyle(ChatFormatting.YELLOW), false);
    }

    /** Un pillard sans cible marche sur le village, puis s'en prend au joueur le plus proche. */
    private static void guider(ServerLevel level, BlockPos centre, Mob mob) {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            return;
        }
        ServerPlayer proche = null;
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isCreative() && !p.isSpectator() && p.distanceToSqr(mob) < 80 * 80
                    && (proche == null || p.distanceToSqr(mob) < proche.distanceToSqr(mob))) {
                proche = p;
            }
        }
        if (proche != null) {
            mob.setTarget(proche);
            return;
        }
        Vec3 vers = Vec3.atCenterOf(centre).subtract(mob.position());
        double d = Math.sqrt(vers.x * vers.x + vers.z * vers.z);
        if (d < 12) {
            return;
        }
        double pas = Math.min(24, d);
        int x = (int) Math.round(mob.getX() + vers.x / d * pas);
        int z = (int) Math.round(mob.getZ() + vers.z / d * pas);
        mob.getNavigation().moveTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, 1.0);
    }

    // ================================================================ les vagues

    private static void envoyer(ServerLevel level, BlockPos centre) {
        MinecraftServer server = level.getServer();
        int joueurs = (int) Math.max(1, server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count());
        int w = vague;
        boolean derniere = w == vagues;
        int nombre = 12 + 6 * w + 4 * (joueurs - 1);
        int evocateurs = w - 1;
        int sorcieres = w;
        int ravageurs = Math.min(3, w - 1);
        PILLARDS.clear();
        dureeVague = 0;
        // les trois entrées du sud : la grande porte, puis les passages sud-est et sud-ouest de la palissade
        int r = HubBuilder.rayon() + 10;
        double[][] entrees = {{0, r}, {Math.cos(Math.PI / 4) * r, Math.sin(Math.PI / 4) * r}, {Math.cos(3 * Math.PI / 4) * r, Math.sin(3 * Math.PI / 4) * r}};
        int k = 0;
        List<Mob> cavaliers = new ArrayList<>();
        List<Mob> montures = new ArrayList<>();
        for (int i = 0; i < nombre + evocateurs + sorcieres + ravageurs + (derniere ? 1 : 0); i++) {
            EntityType<? extends Mob> type;
            boolean chef = false;
            if (i < ravageurs) {
                type = EntityType.RAVAGER;
            } else if (i < ravageurs + evocateurs) {
                type = EntityType.EVOKER;
            } else if (i < ravageurs + evocateurs + sorcieres) {
                type = EntityType.WITCH;
            } else if (derniere && i == nombre + evocateurs + sorcieres + ravageurs) {
                type = EntityType.VINDICATOR;
                chef = true;
            } else {
                type = (i % 20) < 11 ? EntityType.PILLAGER : EntityType.VINDICATOR;
            }
            double[] e = entrees[k++ % entrees.length];
            int x = centre.getX() + (int) Math.round(e[0]) + level.random.nextInt(9) - 4;
            int z = centre.getZ() + (int) Math.round(e[1]) + level.random.nextInt(9) - 4;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            Mob m = creer(level, type, x, y, z, w, chef);
            if (m == null) {
                continue;
            }
            if (type == EntityType.RAVAGER) {
                montures.add(m);
            } else if (type == EntityType.PILLAGER && cavaliers.size() < montures.size()) {
                cavaliers.add(m);
            }
        }
        // un pillard à dos de chaque ravageur
        for (int i = 0; i < cavaliers.size(); i++) {
            Mob monture = montures.get(i);
            cavaliers.get(i).moveTo(monture.getX(), monture.getY(), monture.getZ());
            cavaliers.get(i).startRiding(monture, true);
        }
        total = PILLARDS.size();
        String sous = derniere ? "leur chef est avec eux !" : total + " pillards";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            titre(p, "Vague " + w + "/" + vagues, sous);
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3F, 1F);
        }
        if (derniere) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ Dernière vague : le Chef des Pillards du Sud mène l'assaut en personne !")
                    .withStyle(ChatFormatting.DARK_RED), false);
        }
    }

    private static Mob creer(ServerLevel level, EntityType<? extends Mob> type, int x, int y, int z, int w, boolean chef) {
        Mob m = type.create(level);
        if (m == null) {
            return null;
        }
        m.moveTo(x + 0.5, y, z + 0.5, 180F, 0F);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null);
        if (m instanceof Raider raider) {
            raider.setCanJoinRaid(false);
        }
        // plus la vague est avancée, mieux ils sont armés
        boolean diamant = chef || w >= 5;
        if (type != EntityType.RAVAGER && type != EntityType.WITCH) {
            if (w >= 2 || chef) {
                equiper(m, EquipmentSlot.HEAD, diamant ? Items.DIAMOND_HELMET : Items.IRON_HELMET);
                equiper(m, EquipmentSlot.CHEST, diamant ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
            }
            if (w >= 3 || chef) {
                equiper(m, EquipmentSlot.LEGS, diamant ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS);
                equiper(m, EquipmentSlot.FEET, diamant ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS);
            }
        }
        int force = chef ? 1 : w >= 4 ? 1 : w >= 2 ? 0 : -1;
        int resistance = chef ? 1 : w >= 4 ? 0 : -1;
        if (force >= 0) {
            m.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 1200, force));
        }
        if (resistance >= 0) {
            m.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 1200, resistance));
        }
        AttributeInstance vie = m.getAttribute(Attributes.MAX_HEALTH);
        if (vie != null) {
            vie.setBaseValue(chef ? 250 : vie.getBaseValue() * (w >= 3 ? 1.5 : 1));
            m.setHealth(m.getMaxHealth());
        }
        AttributeInstance portee = m.getAttribute(Attributes.FOLLOW_RANGE);
        if (portee != null) {
            portee.setBaseValue(Math.max(portee.getBaseValue(), 48));
        }
        if (chef) {
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
            m.setItemSlot(EquipmentSlot.HEAD, Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
            m.setDropChance(EquipmentSlot.HEAD, 0F);
            m.setDropChance(EquipmentSlot.MAINHAND, 0F);
        }
        String nom = chef ? "Chef des Pillards du Sud" : type == EntityType.RAVAGER ? "Ravageur des marais" : type == EntityType.WITCH ? "Sorcière des marais"
                : type == EntityType.EVOKER ? "Sorcier des Pillards" : "Pillard du Sud";
        m.setCustomName(Component.literal(nom).withStyle(chef ? ChatFormatting.GOLD : ChatFormatting.DARK_RED));
        m.addTag(TAG);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        PILLARDS.put(m.getUUID(), m.blockPosition());
        return m;
    }

    private static void equiper(Mob m, EquipmentSlot slot, net.minecraft.world.item.Item item) {
        m.setItemSlot(slot, new ItemStack(item));
        m.setDropChance(slot, 0F);
    }

    private static void titre(ServerPlayer p, String titre, String sous) {
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(titre).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sous).withStyle(ChatFormatting.GOLD)));
    }

    // ================================================================ après un redémarrage

    @SubscribeEvent
    public static void onDemarrage(ServerStartedEvent event) {
        serveur = event.getServer();
        finir();
    }

    /** Le serveur s'arrête en plein raid : on rend les chunks (sinon ils resteraient chargés pour toujours). */
    @SubscribeEvent
    public static void onArret(ServerStoppingEvent event) {
        finir();
    }

    /** Un pillard d'un raid d'avant le redémarrage : le raid n'existe plus, il repart. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && vagues == 0 && event.getEntity().getTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    private GrandRaid() {
    }
}
