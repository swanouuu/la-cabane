package fr.cabane.donjon;

import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.world.entity.EntityType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le combat de l'arène : Ugrak apparaît (endormi devant le trône) dès que quelqu'un approche de l'arène, se réveille
 * quand on y entre ; mort, le donjon est terminé : chacun reçoit sa récompense, et un portail de sortie s'ouvre au
 * milieu de l'arène. Le donjon reste « terminé » jusqu'à ce qu'un nouveau groupe entre par Jin-woo.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Boss {
    /** Où il dort, agenouillé, face à la porte de l'arène. */
    public static final Vec3 REPOS = new Vec3(0.5, DonjonBuilder.SOL_BAS + 1, 185.5);
    /** Le portail de sortie qui s'ouvre au milieu de l'arène, une fois le boss mort. */
    public static final Vec3 SORTIE_ARENE = DonjonBuilder.ARENE.add(0, 4.5, 0);
    public static final double RAYON_SORTIE_ARENE = 4;
    /** La récompense de chacun. */
    public static final long PRIME = 2500;

    /** Serveur → client : une fenêtre du Système (un titre, des lignes, et un grand mot en couleur, ou rien). */
    public record Annonce(String titre, List<String> lignes, String grand, int couleur) implements CustomPacketPayload {
        public static final Type<Annonce> TYPE = new Type<>(Cabane.id("donjon_annonce"));
        public static final StreamCodec<ByteBuf, Annonce> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Annonce::titre,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Annonce::lignes, ByteBufCodecs.STRING_UTF8, Annonce::grand,
                ByteBufCodecs.INT, Annonce::couleur, Annonce::new);

        @Override
        public Type<Annonce> type() {
            return TYPE;
        }
    }

    /** Serveur → client : le boss est mort (le portail de sortie de l'arène est ouvert), ou pas. */
    public record Victoire(boolean ouverte) implements CustomPacketPayload {
        public static final Type<Victoire> TYPE = new Type<>(Cabane.id("donjon_victoire"));
        public static final StreamCodec<ByteBuf, Victoire> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Victoire::ouverte, Victoire::new);

        @Override
        public Type<Victoire> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1")
                    .playToClient(Annonce.TYPE, Annonce.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.donjon.ClientPortail.annonce(p)))
                    .playToClient(Victoire.TYPE, Victoire.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.donjon.ClientPortail.victoire(p)));
        }
    }

    /** Une fenêtre du Système pour tous ceux qui sont dans le donjon. */
    public static void annoncer(ServerLevel donjon, String titre, String[] lignes, String grand, int couleur) {
        Annonce a = new Annonce(titre, List.of(lignes), grand, couleur);
        for (ServerPlayer p : donjon.players()) {
            PacketDistributor.sendToPlayer(p, a);
        }
    }

    private static List<Ogre> ogres(ServerLevel donjon) {
        return donjon.getEntities(Donjon.OGRE.get(), new AABB(DonjonBuilder.ARENE, DonjonBuilder.ARENE).inflate(80, 60, 80), o -> !o.isRemoved());
    }

    /** Un nouveau groupe entre : le donjon est de nouveau à faire (ses salles se repeuplent s'il n'y a personne dedans). */
    public static void nouveauGroupe(MinecraftServer server) {
        DonjonData data = DonjonData.get(server);
        ServerLevel niveau = Donjon.niveau(server);
        if (niveau != null && niveau.players().isEmpty()) {
            for (Sbire s : sbires(niveau, new AABB(-40, 40, -20, 40, 100, 205))) {
                s.discard();
            }
            PEUPLEES.clear();
            Enigmes.reinitialiser(niveau);
        }
        if (data.vaincu()) {
            data.setVaincu(false);
            ServerLevel donjon = Donjon.niveau(server);
            if (donjon != null) {
                for (ServerPlayer p : donjon.players()) {
                    PacketDistributor.sendToPlayer(p, new Victoire(false));
                }
            }
        }
    }

    /** Retire le boss et tous les sbires (reconstruction du donjon, nouveau groupe). */
    public static void retirer(ServerLevel donjon) {
        for (Ogre o : ogres(donjon)) {
            o.discard();
        }
        for (Sbire s : sbires(donjon, new AABB(-40, 40, -20, 40, 100, 205))) {
            s.discard();
        }
        PEUPLEES.clear();
    }

    private static List<Sbire> sbires(ServerLevel donjon, AABB zone) {
        List<Sbire> l = new ArrayList<>(donjon.getEntities(Donjon.GOBELIN.get(), zone, s -> !s.isRemoved() && s.isAlive()));
        l.addAll(donjon.getEntities(Donjon.BRUTE.get(), zone, s -> !s.isRemoved() && s.isAlive()));
        return l;
    }

    /** Un sbire de cette sorte, posé là, tourné vers ce point, qui sort du sol. */
    static Sbire poserSbire(ServerLevel donjon, EntityType<Sbire> type, double x, double y, double z, Vec3 regard) {
        Sbire s = type.create(donjon);
        if (s == null) {
            return null;
        }
        float lacet = (float) Math.toDegrees(Math.atan2(-(regard.x - x), regard.z - z));
        s.moveTo(x, y, z, lacet, 0);
        s.yBodyRot = lacet;
        s.yHeadRot = lacet;
        s.finalizeSpawn(donjon, donjon.getCurrentDifficultyAt(s.blockPosition()), MobSpawnType.EVENT, null);
        donjon.addFreshEntity(s);
        s.surgir(donjon);
        return s;
    }

    /** Ugrak appelle ses sbires : ils sortent de terre en cercle autour de l'arène (jamais plus de 16 à la fois). */
    static void invoquer(ServerLevel donjon, int gobelins, int brutes) {
        Vec3 c = DonjonBuilder.ARENE;
        int deja = sbires(donjon, new AABB(c, c).inflate(30, 15, 30)).size();
        int place = Math.max(0, 16 - deja);
        Random r = new Random();
        double depart = r.nextDouble() * Math.PI * 2;
        int total = Math.min(place, gobelins + brutes);
        for (int i = 0; i < total; i++) {
            double a = depart + i * Math.PI * 2 / total;
            double d = 15 + r.nextDouble() * 3;
            poserSbire(donjon, i < brutes ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d, c);
        }
    }

    /**
     * Les salles avant l'arène : leur centre (au sol), leur rayon, et qui les garde (gobelins, brutes). On les remplit
     * quand quelqu'un approche, une fois par groupe.
     */
    private record Salle(String nom, Vec3 centre, double rayon, int gobelins, int brutes) {
    }

    private static final double H = DonjonBuilder.SOL + 1;
    private static final List<Salle> SALLES = List.of(
            new Salle("carrefour", new Vec3(0.5, H, 40.5), 8, 5, 1),
            new Salle("croisee_ouest", new Vec3(-33.5, H, 40.5), 3, 3, 0),
            new Salle("croisee_est", new Vec3(34.5, H, 40.5), 3, 3, 0),
            new Salle("braseros", new Vec3(-33.5, H, 73.5), 7, 4, 1),
            new Salle("leviers", new Vec3(34.5, H, 73.5), 7, 4, 1),
            new Salle("indice_ouest", new Vec3(-54.5, H, 11.5), 3, 3, 0),
            new Salle("indice_est", new Vec3(54.5, H, 14.5), 3, 3, 0),
            new Salle("eboulis", new Vec3(-50.5, H, 73.5), 3, 5, 0),
            new Salle("coffre", new Vec3(59.5, H, 58.5), 2, 0, 2),
            new Salle("escalier", new Vec3(0.5, DonjonBuilder.SOL_BAS + 1, 93.5), 2, 3, 0),
            new Salle("piliers", new Vec3(0.5, DonjonBuilder.SOL_BAS + 1, 113.5), 11, 9, 3));
    private static final Set<String> PEUPLEES = new HashSet<>();

    private static void peupler(ServerLevel donjon) {
        for (Salle s : SALLES) {
            if (PEUPLEES.contains(s.nom())) {
                continue;
            }
            boolean proche = donjon.players().stream().anyMatch(p -> !p.isSpectator() && p.distanceToSqr(s.centre()) < 38 * 38);
            if (!proche || !chargee(donjon, s.centre())) {
                continue;
            }
            PEUPLEES.add(s.nom());
            // déjà des sbires là (un redémarrage du serveur) : on n'en rajoute pas
            if (!sbires(donjon, new AABB(s.centre(), s.centre()).inflate(s.rayon() + 6, 10, s.rayon() + 6)).isEmpty()) {
                continue;
            }
            Random r = new Random();
            int n = s.gobelins() + s.brutes();
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * s.rayon();
                double x = s.centre().x + Math.cos(a) * d, z = s.centre().z + Math.sin(a) * d;
                double y = s.nom().equals("escalier") ? DonjonBuilder.SOL_BAS + 1 : s.centre().y;
                if (s.nom().equals("escalier")) {
                    x = s.centre().x + (i - 1) * 1.6;
                    z = s.centre().z + (i % 2) * 1.5;
                }
                poserSbire(donjon, i < s.brutes() ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), x, y, z, new Vec3(0.5, y, -20));
            }
        }
    }

    /** Pas deux apparitions d'Ugrak à moins de 30 s d'écart, quoi qu'il arrive. */
    private static int prochainOgre;

    /** Les entités autour de ce point sont-elles chargées (sinon on ne peut pas savoir qui est là) ? */
    private static boolean chargee(ServerLevel donjon, Vec3 p) {
        return donjon.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.core.BlockPos.containing(p)));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        ServerLevel donjon = Donjon.niveau(server);
        if (donjon == null || donjon.players().isEmpty()) {
            return;
        }
        DonjonData data = DonjonData.get(server);
        if (data.vaincu()) {
            return;
        }
        peupler(donjon);
        // quelqu'un approche de l'arène, et Ugrak n'est pas là : il apparaît, endormi devant le trône
        boolean proche = donjon.players().stream().anyMatch(p -> p.distanceToSqr(DonjonBuilder.ARENE) < 75 * 75);
        // on ne compte les ogres que si les entités de l'arène sont chargées (sinon on en ferait apparaître un de trop)
        if (proche && chargee(donjon, REPOS) && chargee(donjon, DonjonBuilder.ARENE) && server.getTickCount() > prochainOgre && ogres(donjon).isEmpty()) {
            prochainOgre = server.getTickCount() + 20 * 30;
            Ogre o = Donjon.OGRE.get().create(donjon);
            if (o != null) {
                o.moveTo(REPOS.x, REPOS.y, REPOS.z, 180F, 0F);
                o.yBodyRot = 180F;
                o.yHeadRot = 180F;
                o.finalizeSpawn(donjon, donjon.getCurrentDifficultyAt(o.blockPosition()), MobSpawnType.EVENT, null);
                o.setHealth(o.getMaxHealth());
                donjon.addFreshEntity(o);
            }
        }
    }

    /** Ugrak est mort : le donjon est terminé. */
    static void victoire(ServerLevel donjon) {
        MinecraftServer server = donjon.getServer();
        DonjonData.get(server).setVaincu(true);
        Enigmes.finCombat(donjon, true);
        // ses sbires s'effondrent avec lui
        Vec3 c = DonjonBuilder.ARENE;
        for (Sbire s : sbires(donjon, new AABB(c, c).inflate(32, 15, 32))) {
            s.surgir(donjon);
            s.discard();
        }
        annoncer(donjon, "SYSTÈME", new String[] {"Donjon terminé ! " + Ogre.NOM + " est vaincu.", "Récompense : " + Economie.format(PRIME) + " chacun.",
                "Le portail de sortie est ouvert au milieu de l'arène."}, "", 0);
        for (ServerPlayer p : donjon.players()) {
            PacketDistributor.sendToPlayer(p, new Victoire(true));
            Economie.crediter(server, p.getUUID(), PRIME);
            fr.cabane.pnj.Missions.evenement(p, "ugrak");
            p.sendSystemMessage(Component.literal("Donjon de rang S terminé : +" + Economie.format(PRIME)).withStyle(ChatFormatting.GOLD));
            donjon.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            ExperienceOrb.award(donjon, p.position(), 300);
        }
    }

    /** Le portail de sortie de l'arène, une fois le boss mort : y entrer ramène chacun chez soi. */
    static boolean dansSortieArene(ServerPlayer j) {
        if (!DonjonData.get(j.server).vaincu()) {
            return false;
        }
        Vec3 s = SORTIE_ARENE;
        double dz = j.getZ() - s.z, dx = j.getX() - s.x, dy = j.getY() + 1 - s.y;
        return Math.abs(dz) < 0.8 && dx * dx + dy * dy < RAYON_SORTIE_ARENE * RAYON_SORTIE_ARENE;
    }

    /** En arrivant dans le donjon : savoir si le portail de l'arène est déjà ouvert. */
    @SubscribeEvent
    public static void changement(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer j && event.getTo() == Donjon.DIMENSION) {
            PacketDistributor.sendToPlayer(j, new Victoire(DonjonData.get(j.server).vaincu()));
        }
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j && Donjon.dedans(j)) {
            PacketDistributor.sendToPlayer(j, new Victoire(DonjonData.get(j.server).vaincu()));
        }
    }

    private Boss() {
    }
}
