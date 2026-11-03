package fr.cabane.vegeta;

import fr.cabane.Cabane;
import fr.cabane.CabaneEntities;
import fr.cabane.accessoire.Saiyan;
import fr.cabane.client.cinema.ClientCinema;
import fr.cabane.client.cinema.VoteScreen;
import fr.cabane.hub.HubData;
import fr.cabane.hub.HubEvents;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * L'événement « La fierté du Prince » : quand l'hôte l'a armé (/cabane evenement vegeta armer), un clic droit sur
 * Vegeta, tout le monde réuni autour de lui, lance la cinématique Majin Vegeta contre Goku Super Saiyan 2 sur une
 * île qui flotte au-dessus du village. Ensuite on vote (à la majorité) pour aider l'un des deux, et on se bat contre
 * l'autre, en boss, sur l'île ; celui qu'on a aidé se bat à nos côtés.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class EvenementVegeta {
    /** Le serveur ouvre le vote quand la question s'affiche à l'écran (la cinématique dure un peu plus, au cas où). */
    private static final int DUREE_CINEMA = (int) (ClientCinema.DEBUT_VOTE * 20);
    private static final int DUREE_VOTE = 20 * 20;
    private static final int DUREE_COMBAT = 20 * 60 * 6;
    private static final int RAYON_ILE = 17;
    private static final int RAYON_REUNION = 24;

    private enum Phase { RIEN, CINEMA, VOTE, COMBAT, FIN }

    private static Phase phase = Phase.RIEN;
    private static int ticks;
    private static BlockPos centre;
    private static Vec3 retour;
    private static final List<UUID> participants = new ArrayList<>();
    private static final Map<UUID, Integer> votes = new HashMap<>();
    private static final Set<BlockPos> ile = new HashSet<>();
    private static UUID boss;
    private static UUID allie;
    private static boolean victoire;
    private static String issue = "";
    /** Ceux qui étaient morts à la fin du combat : leur récompense de Saiyan les attend à la réapparition. */
    private static final Set<UUID> recompensesEnAttente = new HashSet<>();

    // ================================================================ réseau

    /** Serveur → client : la cinématique commence, au-dessus de ce point. */
    public record Debut(BlockPos centre) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("vegeta_debut"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = BlockPos.STREAM_CODEC.map(Debut::new, Debut::centre);

        @Override
        public Type<Debut> type() {
            return TYPE;
        }
    }

    /** Serveur → client : le vote (ouvert ou fermé), le temps qui reste et les voix de chaque camp. */
    public record Vote(boolean ouvert, int secondes, int goku, int vegeta) implements CustomPacketPayload {
        public static final Type<Vote> TYPE = new Type<>(Cabane.id("vegeta_vote"));
        public static final StreamCodec<ByteBuf, Vote> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Vote::ouvert, ByteBufCodecs.VAR_INT, Vote::secondes,
                ByteBufCodecs.VAR_INT, Vote::goku, ByteBufCodecs.VAR_INT, Vote::vegeta, Vote::new);

        @Override
        public Type<Vote> type() {
            return TYPE;
        }
    }

    /** Client → serveur : mon choix (0 : aider Goku, 1 : aider Vegeta). */
    public record Choix(int camp) implements CustomPacketPayload {
        public static final Type<Choix> TYPE = new Type<>(Cabane.id("vegeta_choix"));
        public static final StreamCodec<ByteBuf, Choix> CODEC = ByteBufCodecs.VAR_INT.map(Choix::new, Choix::camp);

        @Override
        public Type<Choix> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Debut.TYPE, Debut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCinema.demarrer(Vec3.atBottomCenterOf(p.centre()))))
                .playToClient(Vote.TYPE, Vote.CODEC, (p, ctx) -> ctx.enqueueWork(() -> VoteScreen.recevoir(p)))
                .playToServer(Choix.TYPE, Choix.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && phase == Phase.VOTE && participants.contains(joueur.getUUID())) {
                        votes.put(joueur.getUUID(), p.camp() == 0 ? 0 : 1);
                        envoyerVote(joueur.server, true);
                    }
                }));
    }

    // ================================================================ commandes

    /** /cabane evenement vegeta armer|annuler|lancer : l'hôte prépare (ou force) l'événement. */
    public static Component commande(MinecraftServer server, String action, ServerPlayer lanceur) {
        HubData data = HubData.get(server);
        switch (action) {
            case "armer" -> {
                data.setVegeta(1, "");
                HubEvents.rafraichirPnj(server.overworld());
                return Component.literal("L'événement est armé : réunissez tout le monde autour de Vegeta, et clic droit sur lui.");
            }
            case "annuler" -> {
                arreter(server, false);
                data.setVegeta(0, "");
                HubEvents.rafraichirPnj(server.overworld());
                return Component.literal("L'événement Vegeta est annulé.");
            }
            case "lancer" -> {
                if (phase != Phase.RIEN) {
                    return Component.literal("L'événement est déjà en cours.");
                }
                Entity vegeta = trouverVegeta(server.overworld());
                Vec3 lieu = vegeta != null ? vegeta.position() : lanceur != null ? lanceur.position() : null;
                if (lieu == null) {
                    return Component.literal("Vegeta est introuvable (son coin du village n'est pas chargé).");
                }
                lancer(server.overworld(), lieu, server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).toList());
                return Component.literal("C'est parti !");
            }
            default -> {
                return Component.literal("armer, annuler ou lancer");
            }
        }
    }

    private static Entity trouverVegeta(ServerLevel level) {
        BlockPos c = HubData.get(level.getServer()).centre();
        if (c == null) {
            return null;
        }
        for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(c).inflate(120, 60, 120), EvenementVegeta::estVegeta)) {
            return e;
        }
        return null;
    }

    private static boolean estVegeta(Entity e) {
        return e.getTags().contains("cabane_pnj") && e.getName().getString().equals("Vegeta");
    }

    // ================================================================ le déclenchement : clic droit sur Vegeta

    /** L'événement est armé et attend un clic droit sur Vegeta : sa conversation ne doit pas le prendre. */
    public static boolean attendUnClic(Entity e) {
        return estVegeta(e) && e.getServer() != null && HubData.get(e.getServer()).vegeta() == 1 && phase == Phase.RIEN;
    }

    @SubscribeEvent
    public static void clic(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur) || !estVegeta(event.getTarget())) {
            return;
        }
        HubData data = HubData.get(joueur.server);
        if (data.vegeta() != 1 || phase != Phase.RIEN) {
            return;
        }
        Entity vegeta = event.getTarget();
        List<ServerPlayer> tous = joueur.server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).toList();
        List<String> absents = tous.stream().filter(p -> p.level() != vegeta.level() || p.distanceTo(vegeta) > RAYON_REUNION)
                .map(p -> p.getGameProfile().getName()).toList();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!absents.isEmpty()) {
            joueur.sendSystemMessage(Component.literal("Vegeta : « Il en manque. Je ne raconte pas mes histoires deux fois. » (" + String.join(", ", absents) + ")")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            return;
        }
        lancer((ServerLevel) vegeta.level(), vegeta.position(), tous);
    }

    // ================================================================ le déroulé

    private static void lancer(ServerLevel level, Vec3 lieu, List<ServerPlayer> joueurs) {
        retour = lieu;
        centre = BlockPos.containing(lieu).above(80);
        participants.clear();
        votes.clear();
        joueurs.forEach(p -> participants.add(p.getUUID()));
        construireIle(level);
        fr.cabane.hub.HubData.get(level.getServer()).setIleVegeta(centre);
        phase = Phase.CINEMA;
        ticks = 0;
        victoire = false;
        for (ServerPlayer p : joueurs) {
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, DUREE_CINEMA + DUREE_VOTE + 100, 4, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.SATURATION, DUREE_CINEMA + DUREE_VOTE, 0, false, false));
            PacketDistributor.sendToPlayer(p, new Debut(centre));
        }
        level.playSound(null, BlockPos.containing(lieu), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.AMBIENT, 2F, 0.6F);
    }

    /** L'île de roche qui flotte au-dessus du village : le ring du combat. On ne touche qu'à l'air. */
    private static void construireIle(ServerLevel level) {
        ile.clear();
        Random r = new Random(centre.asLong());
        int sol = centre.getY() - 1;
        for (int dx = -RAYON_ILE; dx <= RAYON_ILE; dx++) {
            for (int dz = -RAYON_ILE; dz <= RAYON_ILE; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) + r.nextDouble() * 1.4;
                if (d > RAYON_ILE) {
                    continue;
                }
                int profondeur = 2 + (int) ((1 - d / RAYON_ILE) * 10) + r.nextInt(2);
                for (int k = 0; k < profondeur; k++) {
                    BlockState etat = k == 0 ? (r.nextInt(6) == 0 ? Blocks.GRAVEL : r.nextInt(3) == 0 ? Blocks.COARSE_DIRT : Blocks.STONE).defaultBlockState()
                            : k < 3 ? (r.nextInt(3) == 0 ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState()
                            : (r.nextInt(4) == 0 ? Blocks.TUFF : Blocks.STONE).defaultBlockState();
                    poser(level, centre.offset(dx, -1 - k, dz), etat);
                }
            }
        }
        // quelques gros rochers sur les bords
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + r.nextDouble() * 0.4;
            BlockPos b = centre.offset((int) (Math.cos(a) * (RAYON_ILE - 4)), 0, (int) (Math.sin(a) * (RAYON_ILE - 4)));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 2; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy - 1) + Math.abs(dz) <= 2) {
                            poser(level, b.offset(dx, dy, dz), (r.nextBoolean() ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    private static void poser(ServerLevel level, BlockPos p, BlockState etat) {
        if (level.getBlockState(p).isAir() || ile.contains(p)) {
            level.setBlock(p, etat, Block.UPDATE_CLIENTS);
            ile.add(p.immutable());
        }
    }

    /** Un cratère : les blocs de l'île dans ce rayon disparaissent, le bord est brûlé. */
    private static void cratere(ServerLevel level, BlockPos c, double rayon) {
        int r = (int) Math.ceil(rayon) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    if (!ile.contains(p)) {
                        continue;
                    }
                    double d = Math.sqrt(dx * dx + dy * dy * 2.5 + dz * dz);
                    if (d < rayon) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else if (d < rayon + 1.2 && level.getBlockState(p.above()).isAir()) {
                        level.setBlock(p, (level.random.nextInt(3) == 0 ? Blocks.MAGMA_BLOCK : Blocks.BLACKSTONE).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (phase == Phase.RIEN) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        ticks++;
        switch (phase) {
            case CINEMA -> {
                // les cratères, au même moment que dans la cinématique
                // (Goku passe Super Saiyan 2, Vegeta libère sa puissance, la poussière explose entre eux)
                if (ticks == 702) {
                    cratere(level, centre.offset(-7, -1, 0), 2.2);
                } else if (ticks == 862) {
                    cratere(level, centre.offset(7, -1, 0), 2.4);
                } else if (ticks > 927 && ticks < 960 && ticks % 8 == 0) {
                    Random r = new Random(ticks);
                    cratere(level, centre.offset(r.nextInt(11) - 5, -1, r.nextInt(9) - 4), 1.5);
                }
                if (ticks >= DUREE_CINEMA) {
                    phase = Phase.VOTE;
                    ticks = 0;
                    envoyerVote(server, true);
                }
            }
            case VOTE -> {
                if (ticks % 20 == 0) {
                    envoyerVote(server, true);
                }
                long presents = participants.stream().filter(u -> server.getPlayerList().getPlayer(u) != null).count();
                if (ticks >= DUREE_VOTE || votes.size() >= presents) {
                    depouiller(server);
                }
            }
            case COMBAT -> combat(server, level);
            case FIN -> {
                if (ticks >= 200) {
                    arreter(server, true);
                }
            }
            default -> {
            }
        }
    }

    private static void envoyerVote(MinecraftServer server, boolean ouvert) {
        int goku = (int) votes.values().stream().filter(v -> v == 0).count();
        int vegeta = votes.size() - goku;
        int restant = Math.max(0, (DUREE_VOTE - ticks) / 20);
        for (ServerPlayer p : joueurs(server)) {
            PacketDistributor.sendToPlayer(p, new Vote(ouvert, restant, goku, vegeta));
        }
    }

    private static List<ServerPlayer> joueurs(MinecraftServer server) {
        List<ServerPlayer> l = new ArrayList<>();
        for (UUID u : participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p != null) {
                l.add(p);
            }
        }
        return l;
    }

    private static void titre(ServerPlayer p, String titre, ChatFormatting couleur, String sous) {
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(titre).withStyle(couleur, ChatFormatting.BOLD)));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sous).withStyle(ChatFormatting.WHITE)));
    }

    /** Le vote est clos : à la majorité, et en cas d'égalité c'est la fierté du Prince qui l'emporte. */
    private static void depouiller(MinecraftServer server) {
        int goku = (int) votes.values().stream().filter(v -> v == 0).count();
        int vegeta = votes.size() - goku;
        issue = goku > vegeta ? "goku" : "vegeta";
        envoyerVote(server, false);
        ServerLevel level = server.overworld();
        List<ServerPlayer> joueurs = joueurs(server);
        boolean aideGoku = issue.equals("goku");
        for (int i = 0; i < joueurs.size(); i++) {
            ServerPlayer p = joueurs.get(i);
            p.removeEffect(MobEffects.DAMAGE_RESISTANCE);
            p.removeEffect(MobEffects.SATURATION);
            double a = Math.PI / 2 + (i - (joueurs.size() - 1) / 2.0) * 0.35;
            Vec3 place = Vec3.atBottomCenterOf(centre).add(-Math.cos(a) * 6 - 4, 0.2, Math.sin(a) * 6);
            p.teleportTo(level, place.x, place.y, place.z, aideGoku ? -90F : -90F, 0F);
            p.fallDistance = 0;
            titre(p, aideGoku ? "VOUS AIDEZ GOKU" : "VOUS AIDEZ VEGETA", aideGoku ? ChatFormatting.GOLD : ChatFormatting.DARK_PURPLE,
                    goku + " contre " + vegeta + (goku == vegeta ? " : la fierté du Prince l'emporte !" : ""));
        }
        CombattantZ b = CabaneEntities.COMBATTANT_Z.get().create(level);
        CombattantZ a = CabaneEntities.COMBATTANT_Z.get().create(level);
        if (b == null || a == null) {
            arreter(server, false);
            return;
        }
        b.preparer(aideGoku ? CombattantZ.VEGETA : CombattantZ.GOKU, false, joueurs.size());
        a.preparer(aideGoku ? CombattantZ.GOKU : CombattantZ.VEGETA, true, joueurs.size());
        Vec3 c = Vec3.atBottomCenterOf(centre);
        b.moveTo(c.x + 8, c.y + 3, c.z, 90F, 0F);
        a.moveTo(c.x - 10, c.y + 3, c.z, -90F, 0F);
        level.addFreshEntity(b);
        level.addFreshEntity(a);
        boss = b.getUUID();
        allie = a.getUUID();
        phase = Phase.COMBAT;
        ticks = 0;
        server.getPlayerList().broadcastSystemMessage(Component.literal(aideGoku
                ? "Goku : « Merci ! On va le libérer de Babidi, ensemble ! »"
                : "Vegeta : « Hmph. Enfin des gens qui ont du goût. Écrasons Kakarot ! »")
                .withStyle(aideGoku ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE), false);
    }

    private static void combat(MinecraftServer server, ServerLevel level) {
        List<ServerPlayer> joueurs = joueurs(server);
        Vec3 c = Vec3.atBottomCenterOf(centre);
        // qui tombe de l'île y est ramené (le Prince ne laisse personne fuir)
        for (ServerPlayer p : joueurs) {
            if (p.level() == level && p.getY() < c.y - 18 && p.position().subtract(c).horizontalDistance() < 60 && p.isAlive()) {
                p.teleportTo(level, c.x - 4, c.y + 0.5, c.z, p.getYRot(), 0F);
                p.fallDistance = 0;
                p.displayClientMessage(Component.literal("On ne s'enfuit pas pendant un combat de Saiyans !").withStyle(ChatFormatting.RED), true);
            }
        }
        boolean personne = joueurs.stream().noneMatch(p -> p.isAlive() && p.level() == level && p.position().distanceTo(c) < 80);
        if (ticks > 100 && (personne || ticks > DUREE_COMBAT)) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("Vegeta : « Pitoyable. Revenez me voir quand vous serez prêts. » (l'événement pourra être relancé)")
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            // perdu ou gagné, ceux qui ont combattu repartent avec leurs accessoires de Saiyan
            joueurs.forEach(EvenementVegeta::recompenseSaiyan);
            arreter(server, false);
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (phase != Phase.COMBAT || !event.getEntity().getUUID().equals(boss) || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        victoire = true;
        boolean aideGoku = issue.equals("goku");
        HubData.get(server).setVegeta(2, issue);
        for (ServerPlayer p : joueurs(server)) {
            titre(p, "VICTOIRE !", ChatFormatting.GOLD, aideGoku ? "Vegeta est libéré du sort de Babidi" : "Le Prince des Saiyans a vaincu Kakarot");
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            fr.cabane.economie.Economie.crediter(server, p.getUUID(), 3000);
            ItemStack diamants = new ItemStack(Items.DIAMOND, 3);
            if (!p.getInventory().add(diamants)) {
                p.drop(diamants, false);
            }
            p.sendSystemMessage(Component.literal("★ +3 000 $ et 3 diamants pour la bataille du ciel de Beauvais.").withStyle(ChatFormatting.GOLD));
            recompenseSaiyan(p);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(aideGoku
                ? "Vegeta : « … Le M a disparu. Ne croyez pas que je vous dois quelque chose. … Merci. »"
                : "Goku : « Hahaha ! T'es devenu vraiment fort, Vegeta ! On remet ça quand tu veux ! »")
                .withStyle(aideGoku ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GOLD), false);
        phase = Phase.FIN;
        ticks = 0;
    }

    /**
     * La récompense du combat, qu'il soit gagné ou perdu : une queue de Saiyan et un détecteur d'une couleur au hasard
     * (accessoire/Saiyan), à porter d'un clic droit. Un joueur mort à ce moment-là la reçoit en réapparaissant.
     */
    private static void recompenseSaiyan(ServerPlayer p) {
        if (!p.isAlive()) {
            recompensesEnAttente.add(p.getUUID());
            return;
        }
        int couleur = p.getRandom().nextInt(Saiyan.COULEURS.size());
        for (ItemStack s : List.of(new ItemStack(Saiyan.QUEUE.get()), new ItemStack(Saiyan.DETECTEURS.get(couleur).get()))) {
            if (!p.getInventory().add(s)) {
                p.drop(s, false);
            }
        }
        p.sendSystemMessage(Component.literal("★ Récompense de Saiyan : une queue de Saiyan et un détecteur " + Saiyan.COULEURS.get(couleur) + ". ")
                .append(Component.keybind("key.use")).append(" avec l'objet en main pour le porter.").withStyle(ChatFormatting.GOLD));
    }

    @SubscribeEvent
    public static void reapparition(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && recompensesEnAttente.remove(p.getUUID())) {
            recompenseSaiyan(p);
        }
    }

    /** Fin de l'événement : on redescend au village, l'île disparaît, Vegeta change de discours. */
    private static void arreter(MinecraftServer server, boolean fini) {
        ServerLevel level = server.overworld();
        for (UUID u : new UUID[] {boss, allie}) {
            Entity e = u == null ? null : level.getEntity(u);
            if (e != null) {
                e.discard();
            }
        }
        for (ServerPlayer p : joueurs(server)) {
            p.removeEffect(MobEffects.DAMAGE_RESISTANCE);
            if (retour != null && centre != null && p.position().distanceTo(Vec3.atCenterOf(centre)) < 80) {
                p.teleportTo(level, retour.x, retour.y, retour.z, p.getYRot(), 0F);
                p.fallDistance = 0;
            }
        }
        for (BlockPos p : ile) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        ile.clear();
        effacerIle(level, centre);
        fr.cabane.hub.HubData.get(server).setIleVegeta(null);
        phase = Phase.RIEN;
        boss = null;
        allie = null;
        participants.clear();
        votes.clear();
        if (fini && victoire) {
            HubEvents.rafraichirPnj(level);
        }
    }

    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        phase = Phase.RIEN;
        ile.clear();
        participants.clear();
        recompensesEnAttente.clear();
        // le serveur s'est arrêté en plein combat : l'île est restée dans le ciel, on l'efface
        var donnees = fr.cabane.hub.HubData.get(event.getServer());
        if (donnees.ileVegeta() != null) {
            effacerIle(event.getServer().overworld(), donnees.ileVegeta());
            donnees.setIleVegeta(null);
        }
    }

    /**
     * Efface tout ce qui reste de l'île (elle est seule dans le ciel, 80 blocs au-dessus du village) : un cylindre un
     * peu plus large qu'elle, du dessous de sa roche jusqu'au-dessus de ses rochers.
     */
    private static void effacerIle(ServerLevel level, BlockPos c) {
        if (c == null) {
            return;
        }
        int r = RAYON_ILE + 3;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int dy = -16; dy <= 8; dy++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private EvenementVegeta() {
    }
}
