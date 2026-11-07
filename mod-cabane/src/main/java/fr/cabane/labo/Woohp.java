package fr.cabane.labo;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le WOOHP, comme dans Totally Spies. L'hôte (Jerry) tape /whoop suivi d'un à trois pseudos. Où qu'elles soient, le son
 * du WOOHP retentit ; une seconde plus tard les espionnes sont tirées en arrière à toute vitesse au ras du sol (tout le
 * monde autour les voit filer), un anneau jaune et rose s'ouvre par terre et elles rapetissent dedans. Elles se
 * retrouvent assises dans un chariot au sommet d'un grand toboggan de verre, au milieu du vide, qui les dépose chacune
 * dans son tube au QG, face au bureau de Jerry. Jerry, lui, y va directement avec /whoop tout court.
 *
 * <p>Au QG : les gadgets que Jerry met dans son coffre caché sortent un par un sur le tapis roulant, devant les
 * espionnes. Pour repartir, le bouton du retour (ou /whoop renvoyer) les ramène pile là où elles étaient.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Woohp {
    /** Le son, puis l'aspiration (le jeu de l'espionne la fait glisser), l'anneau et le rétrécissement, puis le toboggan. */
    public static final int TIRAGE = 20;
    public static final int ANNEAU = 41;
    public static final int TOBOGGAN = 50;
    /** Un gadget sort du distributeur toutes les… (ticks). */
    private static final int CADENCE = 30;
    /** Le rétrécissement dans le trou (un modificateur de taille, retiré en haut du toboggan). */
    public static final ResourceLocation RETRECIR = Cabane.id("woohp_aspiration");
    private static final String CHARIOT = "woohp.chariot";

    /** Une espionne en route : son tube, le tick du serveur où le son a retenti, et son chariot dans le toboggan. */
    private static final class Trajet {
        final int tube;
        final long debut;
        AbstractMinecart chariot;
        /** Au bout du toboggan : ailleurs que le QG (le départ d'une mission, voir mission/Missions.java). */
        java.util.function.Consumer<ServerPlayer> arrivee;

        Trajet(int tube, long debut) {
            this.tube = tube;
            this.debut = debut;
        }
    }

    /**
     * Le même départ que /whoop (le son, l'aspiration, l'anneau, le toboggan), mais au bout du toboggan {@code arrivee} prend
     * la main au lieu du pad du QG. Faux si l'espionne est déjà en route ou si la dimension du labo manque.
     */
    public static boolean aspirer(ServerPlayer j, int tube, java.util.function.Consumer<ServerPlayer> arrivee) {
        if (!pret(j.server) || TRAJETS.containsKey(j.getUUID())) {
            return false;
        }
        if (j.isSleeping()) {
            j.stopSleepInBed(true, true);
        }
        j.stopRiding();
        Trajet t = new Trajet(tube, j.server.getTickCount());
        t.arrivee = arrivee;
        TRAJETS.put(j.getUUID(), t);
        j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, TOBOGGAN + 10, 4, false, false));
        j.serverLevel().playSound(j, j.getX(), j.getY(), j.getZ(), Labo.SON_WOOHP.get(), SoundSource.PLAYERS, 1.6F, 1F);
        PacketDistributor.sendToPlayer(j, new Etape(0, tube));
        return true;
    }

    private static final Map<UUID, Trajet> TRAJETS = new HashMap<>();
    private static long prochainGadget;

    // ------------------------------------------------------------------ les messages vers le jeu des espionnes

    /** {@code etape} : 0 le son retentit (l'aspiration suit), 1 on est dans le chariot en haut du toboggan, 2 on est arrivé. */
    public record Etape(int etape, int tube) implements CustomPacketPayload {
        public static final Type<Etape> TYPE = new Type<>(Cabane.id("woohp_etape"));
        public static final StreamCodec<ByteBuf, Etape> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Etape::etape, ByteBufCodecs.VAR_INT, Etape::tube, Etape::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToClient(Etape.TYPE, Etape.CODEC,
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.ClientWoohp.recevoir(p.etape(), p.tube())));
        }
    }

    // ------------------------------------------------------------------ le QG

    /** Le joueur est-il au QG (ou dans ses toboggans) ? */
    static boolean pres(ServerPlayer joueur) {
        BlockPos o = WoohpTrajet.ORIGINE;
        // le QG, ses toboggans et l'aile des salles d'entraînement (à l'ouest)
        return Labo.dedans(joueur) && joueur.getX() > o.getX() + WoohpBuilder.COULOIR_FIN - 30 && joueur.getX() < o.getX() + WoohpBuilder.BORD_X + 30
                && joueur.getZ() > o.getZ() - WoohpBuilder.AILE_Z - 30 && joueur.getZ() < o.getZ() + WoohpBuilder.BORD_Z1 + 30;
    }

    /** Construit le QG s'il n'existe pas encore (ou si on l'a changé depuis). Faux si la dimension manque. */
    private static boolean pret(MinecraftServer server) {
        ServerLevel labo = Labo.niveau(server);
        if (labo == null) {
            return false;
        }
        if (LaboData.get(server).versionWoohp() != WoohpBuilder.VERSION) {
            construire(labo);
        }
        return true;
    }

    private static void construire(ServerLevel labo) {
        int numero = LaboData.get(labo.getServer()).nouvelleConstructionWoohp(WoohpBuilder.VERSION);
        WoohpBuilder.construire(labo, numero);
        biome(labo);
    }

    /** Le QG a son propre biome : pas de poussière, pas de bruits de grotte, pas de musique angoissante. */
    private static void biome(ServerLevel labo) {
        Holder<Biome> biome = labo.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolder(ResourceKey.create(Registries.BIOME, Cabane.id("woohp"))).orElse(null);
        if (biome == null) {
            return;
        }
        BlockPos o = WoohpTrajet.ORIGINE;
        for (int cx = (o.getX() + WoohpBuilder.COULOIR_FIN - 8) >> 4; cx <= (o.getX() + WoohpBuilder.BORD_X + 8) >> 4; cx++) {
            for (int cz = (o.getZ() - WoohpBuilder.AILE_Z - 8) >> 4; cz <= (o.getZ() + WoohpBuilder.BORD_Z1 + 8) >> 4; cz++) {
                labo.getChunk(cx, cz);
                FillBiomeCommand.fill(labo, new BlockPos(cx << 4, o.getY() - 24, cz << 4), new BlockPos((cx << 4) + 15, o.getY() + 56, (cz << 4) + 15), biome);
            }
        }
    }

    /** Pose le joueur sur le pad de son tube, face au bureau de Jerry. */
    static void poserSurLePad(ServerPlayer joueur, int tube) {
        ServerLevel labo = Labo.niveau(joueur.server);
        BlockPos o = WoohpTrajet.ORIGINE;
        joueur.teleportTo(labo, o.getX() + WoohpTrajet.TUBES_X[Math.floorMod(tube, 3)] + 0.5, o.getY() + 1, o.getZ() + WoohpTrajet.TUBE_Z + 0.5, 0F, 5F);
        joueur.fallDistance = 0;
    }

    /** Retient d'où vient le joueur (seulement s'il vient du vrai monde) : le bouton du retour l'y ramènera. */
    private static void retenirLeDepart(ServerPlayer joueur) {
        if (!Labo.dedans(joueur)) {
            LaboData.get(joueur.server).setRetour(joueur.getUUID(), new LaboData.Retour(joueur.level().dimension().location(),
                    joueur.getX(), joueur.getY(), joueur.getZ(), joueur.getYRot(), joueur.getXRot()));
        }
    }

    // ------------------------------------------------------------------ /whoop

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var cible = Commands.argument("espionne1", EntityArgument.player()).executes(ctx -> whooper(ctx, 1))
                .then(Commands.argument("espionne2", EntityArgument.player()).executes(ctx -> whooper(ctx, 2))
                        .then(Commands.argument("espionne3", EntityArgument.player()).executes(ctx -> whooper(ctx, 3))));
        event.getDispatcher().register(Commands.literal("whoop").requires(Labo::hote)
                // tout court : Jerry va à son bureau
                .executes(ctx -> bureau(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("renvoyer").executes(ctx -> renvoyer(ctx.getSource(), null))
                        .then(Commands.argument("joueurs", EntityArgument.players())
                                .executes(ctx -> renvoyer(ctx.getSource(), EntityArgument.getPlayers(ctx, "joueurs")))))
                .then(Commands.literal("reconstruire").executes(ctx -> {
                    ServerLevel labo = Labo.niveau(ctx.getSource().getServer());
                    if (labo == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
                        return 0;
                    }
                    construire(labo);
                    ctx.getSource().sendSuccess(() -> Component.literal("Le QG du WOOHP est reconstruit."), true);
                    return 1;
                }))
                .then(cible));
    }

    private static int whooper(CommandContext<CommandSourceStack> ctx, int nombre) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        if (!pret(server)) {
            ctx.getSource().sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
            return 0;
        }
        List<ServerPlayer> espionnes = new ArrayList<>();
        for (int i = 1; i <= nombre; i++) {
            ServerPlayer j = EntityArgument.getPlayer(ctx, "espionne" + i);
            if (!espionnes.contains(j)) {
                espionnes.add(j);
            }
        }
        long maintenant = server.getTickCount();
        for (int i = 0; i < espionnes.size(); i++) {
            ServerPlayer j = espionnes.get(i);
            if (TRAJETS.containsKey(j.getUUID())) {
                continue;
            }
            if (j.isSleeping()) {
                j.stopSleepInBed(true, true);
            }
            j.stopRiding();
            retenirLeDepart(j);
            TRAJETS.put(j.getUUID(), new Trajet(i, maintenant));
            j.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, TOBOGGAN + 10, 4, false, false));
            // le son du WOOHP pour ceux qui sont autour ; elle, son jeu le lui joue dans les oreilles (ClientWoohp)
            j.serverLevel().playSound(j, j.getX(), j.getY(), j.getZ(), Labo.SON_WOOHP.get(), SoundSource.PLAYERS, 1.6F, 1F);
            PacketDistributor.sendToPlayer(j, new Etape(0, i));
        }
        // Jerry entend aussi le WOOHP qu'il vient d'envoyer, sur le volume général (pas celui des joueurs, souvent baissé)
        ServerPlayer jerry = ctx.getSource().getPlayer();
        if (jerry != null && !espionnes.contains(jerry)) {
            jerry.playNotifySound(Labo.SON_WOOHP.get(), SoundSource.MASTER, 1F, 1F);
        }
        // pas de message dans le chat : la surprise reste entière
        return espionnes.size();
    }

    /** /whoop tout court : Jerry s'installe à son bureau. */
    private static int bureau(ServerPlayer jerry) {
        if (!pret(jerry.server)) {
            return 0;
        }
        retenirLeDepart(jerry);
        BlockPos o = WoohpTrajet.ORIGINE, p = WoohpBuilder.JERRY;
        jerry.teleportTo(Labo.niveau(jerry.server), o.getX() + p.getX() + 0.5, o.getY() + p.getY() + 0.1, o.getZ() + p.getZ() + 0.5, 180F, 10F);
        Labo.verifierMode(jerry);
        jerry.displayClientMessage(Component.literal("Bonjour Jerry. Ton coffre caché est dans le bureau, juste à ta gauche.").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    private static int renvoyer(CommandSourceStack source, java.util.Collection<ServerPlayer> joueurs) {
        List<ServerPlayer> cibles = joueurs != null ? List.copyOf(joueurs)
                : source.getServer().getPlayerList().getPlayers().stream().filter(Woohp::pres).filter(j -> j != source.getPlayer()).toList();
        cibles.forEach(Labo::sortir);
        source.sendSuccess(() -> Component.literal(cibles.size() + " espionne(s) renvoyée(s) chez elle(s)."), true);
        return cibles.size();
    }

    // ------------------------------------------------------------------ le trajet, et le tapis roulant

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long maintenant = server.getTickCount();
        int glissade = (int) Math.ceil(WoohpTrajet.DUREE * 20);
        Iterator<Map.Entry<UUID, Trajet>> it = TRAJETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Trajet> e = it.next();
            ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
            Trajet t = e.getValue();
            long ecoule = maintenant - t.debut;
            if (j == null) {
                it.remove();
                if (t.chariot != null) {
                    t.chariot.discard();
                }
                continue;
            }
            if (ecoule == TIRAGE) {
                // le bruit d'aspiration, pour tous ceux qui sont autour
                j.serverLevel().playSound(null, j.getX(), j.getY(), j.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 2F, 0.6F);
            }
            if (ecoule == ANNEAU) {
                anneau(j);
            }
            if (ecoule >= ANNEAU && ecoule < TOBOGGAN) {
                // elle rapetisse dans le trou
                retrecir(j, (ecoule - ANNEAU + 1) / (double) (TOBOGGAN - ANNEAU));
            } else if (ecoule == TOBOGGAN) {
                retrecir(j, -1);
                monterDansLeChariot(j, t);
            } else if (ecoule > TOBOGGAN && ecoule <= TOBOGGAN + glissade) {
                if (ecoule == TOBOGGAN + 3) {
                    // une deuxième fois, au cas où son jeu chargeait encore le monde du QG
                    envoyerTaille(j);
                }
                deplacerChariot(t, (ecoule - TOBOGGAN) / 20.0);
            } else if (ecoule > TOBOGGAN + glissade + 3) {
                it.remove();
                arriver(j, t);
            }
        }
        ServerLevel labo = Labo.niveau(server);
        if (labo != null && maintenant >= prochainGadget && maintenant % 5 == 0) {
            distribuer(labo, maintenant);
        }
    }

    /** L'anneau jaune et rose qui s'ouvre sous ses pieds, au bout de l'aspiration (tout le monde le voit). */
    private static void anneau(ServerPlayer j) {
        ServerLevel level = j.serverLevel();
        DustParticleOptions jaune = new DustParticleOptions(new Vector3f(1F, 0.85F, 0.1F), 1.8F);
        DustParticleOptions rose = new DustParticleOptions(new Vector3f(1F, 0.3F, 0.8F), 1.6F);
        for (int a = 0; a < 48; a++) {
            double angle = a * Math.PI * 2 / 48;
            for (double r : new double[] {0.8, 1.3}) {
                level.sendParticles(a % 2 == 0 ? jaune : rose, j.getX() + Math.cos(angle) * r, j.getY() + 0.1, j.getZ() + Math.sin(angle) * r, 1, 0, 0.05, 0, 0);
            }
        }
        level.sendParticles(ParticleTypes.PORTAL, j.getX(), j.getY() + 0.5, j.getZ(), 40, 0.3, 0.5, 0.3, 0.6);
        level.playSound(null, j.getX(), j.getY(), j.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.2F, 0.8F);
    }

    /** {@code k} de 0 à 1 : la taille diminue jusqu'à presque rien ; -1 : la taille normale revient. */
    private static void retrecir(ServerPlayer j, double k) {
        AttributeInstance taille = j.getAttribute(Attributes.SCALE);
        if (taille == null) {
            return;
        }
        if (k < 0) {
            taille.removeModifier(RETRECIR);
        } else {
            taille.addOrUpdateTransientModifier(new AttributeModifier(RETRECIR, -0.93 * k, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** En haut du toboggan : on s'assoit dans un chariot, qui ne se quitte pas avant l'arrivée. */
    private static void monterDansLeChariot(ServerPlayer j, Trajet t) {
        ServerLevel labo = Labo.niveau(j.server);
        Vec3 p = WoohpTrajet.position(t.tube, 0).subtract(0, WoohpTrajet.SIEGE, 0);
        Vec3 d = WoohpTrajet.direction(t.tube, 0);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        j.teleportTo(labo, p.x, p.y + 0.5, p.z, lacet, 15F);
        Labo.verifierMode(j);
        AbstractMinecart chariot = EntityType.MINECART.create(labo);
        if (chariot != null) {
            chariot.moveTo(p.x, p.y, p.z, lacet + 90F, 0F);
            chariot.setNoGravity(true);
            chariot.setInvulnerable(true);
            chariot.addTag(CHARIOT);
            labo.addFreshEntity(chariot);
            j.startRiding(chariot, true);
            t.chariot = chariot;
        }
        envoyerTaille(j);
        PacketDistributor.sendToPlayer(j, new Etape(1, t.tube));
        labo.playSound(null, p.x, p.y, p.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1F, 1.1F);
    }

    /**
     * Sa taille normale, envoyée tout de suite à son jeu : après le changement de monde, il gardait sinon la toute petite
     * taille du trou, et la caméra restait au fond du chariot pendant tout le toboggan.
     */
    private static void envoyerTaille(ServerPlayer j) {
        AttributeInstance taille = j.getAttribute(Attributes.SCALE);
        if (taille != null) {
            j.connection.send(new ClientboundUpdateAttributesPacket(j.getId(), List.of(taille)));
        }
    }

    private static void deplacerChariot(Trajet t, double secondes) {
        if (t.chariot == null || t.chariot.isRemoved()) {
            return;
        }
        Vec3 p = WoohpTrajet.position(t.tube, secondes).subtract(0, WoohpTrajet.SIEGE, 0);
        Vec3 d = WoohpTrajet.direction(t.tube, secondes);
        float lacet = Math.abs(d.y) > 0.95 ? t.chariot.getYRot() : (float) Math.toDegrees(Math.atan2(-d.x, d.z)) + 90F;
        // pas de vitesse propre : sinon le chariot avancerait une seconde fois tout seul avant qu'on le replace
        t.chariot.setDeltaMovement(Vec3.ZERO);
        t.chariot.moveTo(p.x, p.y, p.z, lacet, 0F);
    }

    private static void arriver(ServerPlayer j, Trajet t) {
        j.stopRiding();
        if (t.chariot != null) {
            // son jeu apprend tout de suite que le chariot est vide : elle en sort direct, sans devoir appuyer sur Maj
            j.connection.send(new ClientboundSetPassengersPacket(t.chariot));
            t.chariot.discard();
        }
        if (t.arrivee != null) {
            PacketDistributor.sendToPlayer(j, new Etape(2, t.tube));
            t.arrivee.accept(j);
            return;
        }
        poserSurLePad(j, t.tube);
        PacketDistributor.sendToPlayer(j, new Etape(2, t.tube));
        ServerLevel labo = j.serverLevel();
        labo.playSound(null, j.getX(), j.getY(), j.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 1.2F);
        labo.sendParticles(ParticleTypes.CLOUD, j.getX(), j.getY() + 0.3, j.getZ(), 25, 0.6, 0.1, 0.6, 0.05);
        labo.sendParticles(ParticleTypes.END_ROD, j.getX(), j.getY() + 1, j.getZ(), 20, 0.4, 1.2, 0.4, 0.02);
        j.displayClientMessage(Component.literal("Bienvenue au WOOHP !").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), true);
    }

    /** On ne descend pas du chariot en plein toboggan. */
    @SubscribeEvent
    public static void onDescente(EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityMounting() instanceof ServerPlayer j && TRAJETS.containsKey(j.getUUID())) {
            event.setCanceled(true);
        }
    }

    /** Un chariot resté là après un redémarrage du serveur (ou une déconnexion) : on le retire. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().getTags().contains(CHARIOT) && event.loadedFromDisk()) {
            event.setCanceled(true);
        }
    }

    /** Le coffre caché de Jerry : un gadget à la fois sort du distributeur sur le tapis roulant. */
    private static void distribuer(ServerLevel labo, long maintenant) {
        BlockPos coffre = WoohpBuilder.abs(WoohpBuilder.COFFRE.getX(), WoohpBuilder.COFFRE.getY(), WoohpBuilder.COFFRE.getZ());
        if (!labo.isLoaded(coffre) || !(labo.getBlockEntity(coffre) instanceof Container c)) {
            return;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            ItemStack gadget = c.removeItem(i, s.getCount());
            c.setChanged();
            Vec3 depart = Vec3.atBottomCenterOf(WoohpBuilder.abs(WoohpBuilder.TAPIS_X0, 2, WoohpBuilder.TAPIS_Z)).add(-0.2, 0.05, 0);
            ItemEntity objet = new ItemEntity(labo, depart.x, depart.y, depart.z, gadget, 0.15, 0, 0);
            objet.setPickUpDelay(10);
            objet.setUnlimitedLifetime();
            labo.addFreshEntity(objet);
            BlockPos d = WoohpBuilder.abs(WoohpBuilder.TAPIS_X0 - 1, 1, WoohpBuilder.TAPIS_Z);
            labo.playSound(null, d, SoundEvents.DISPENSER_DISPENSE, SoundSource.BLOCKS, 1F, 1.2F);
            labo.playSound(null, d, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, 1.5F);
            labo.sendParticles(ParticleTypes.WAX_ON, depart.x, depart.y + 0.3, depart.z, 6, 0.2, 0.2, 0.2, 0.5);
            prochainGadget = maintenant + CADENCE;
            return;
        }
    }

    /** Le coffre caché n'est qu'à Jerry : les espionnes ne peuvent pas l'ouvrir. */
    @SubscribeEvent
    public static void onClic(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer j && Labo.dedans(j)
                && event.getPos().equals(WoohpBuilder.abs(WoohpBuilder.COFFRE.getX(), WoohpBuilder.COFFRE.getY(), WoohpBuilder.COFFRE.getZ()))
                && !Labo.hote(j.createCommandSourceStack())) {
            event.setCanceled(true);
            j.displayClientMessage(Component.literal("Ce tiroir est fermé à clé. C'est celui de Jerry.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** Déconnecté en plein toboggan : au retour, on le remet sur son pad, à sa taille. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            retrecir(j, -1);
            BlockPos o = WoohpTrajet.ORIGINE;
            boolean dansLeToboggan = pres(j) && (Math.abs(j.getX() - o.getX()) > WoohpBuilder.X || Math.abs(j.getZ() - o.getZ()) > WoohpBuilder.Z
                    || j.getY() > o.getY() + WoohpBuilder.HAUT);
            if (dansLeToboggan) {
                poserSurLePad(j, 0);
            }
        }
    }

    private Woohp() {
    }
}
