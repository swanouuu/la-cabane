package fr.cabane.donjon;

import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les combats des arènes, pour tous les donjons : le boss apparaît (endormi à sa place) dès que quelqu'un approche de
 * l'arène, se réveille quand on y entre ; mort, le donjon est terminé : chacun reçoit sa récompense, et un portail de
 * sortie s'ouvre au milieu de l'arène. Les salles se remplissent de sbires quand on en approche, une fois par groupe.
 * Chaque donjon a son groupe à lui : rien de ce qui se passe dans l'un ne touche les autres.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Boss {
    /** Où Ugrak dort, agenouillé, face à la porte de l'arène. */
    public static final Vec3 REPOS = new Vec3(0.5, DonjonBuilder.SOL_BAS + 1, 185.5);
    public static final double RAYON_SORTIE_ARENE = 4;
    /** La récompense de chacun, à chaque donjon terminé. */
    public static final long PRIME = 1500;

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

    /** Serveur → client : le boss de ton donjon est mort (le portail de sortie de l'arène est ouvert), ou pas. */
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

    /** Les joueurs qui sont dans ce donjon. */
    public static List<ServerPlayer> joueurs(ServerLevel l, TypeDonjon type) {
        List<ServerPlayer> j = new ArrayList<>();
        for (ServerPlayer p : l.players()) {
            if (type.contient(p.getX())) {
                j.add(p);
            }
        }
        return j;
    }

    /** Une fenêtre du Système pour tous ceux qui sont dans ce donjon. */
    public static void annoncer(ServerLevel donjon, TypeDonjon type, String titre, String[] lignes, String grand, int couleur) {
        Annonce a = new Annonce(titre, List.of(lignes), grand, couleur);
        for (ServerPlayer p : joueurs(donjon, type)) {
            PacketDistributor.sendToPlayer(p, a);
        }
    }

    /** Dans la grotte d'Ugrak. */
    public static void annoncer(ServerLevel donjon, String titre, String[] lignes, String grand, int couleur) {
        annoncer(donjon, TypeDonjon.OGRE, titre, lignes, grand, couleur);
    }

    /** La boîte de tout un donjon. */
    static AABB zone(TypeDonjon type) {
        return new AABB(type.x0() - 80, 40, -20, type.x0() + 80, 100, 215);
    }

    static List<Mob> boss(ServerLevel donjon, TypeDonjon type) {
        List<Mob> l = new ArrayList<>();
        if (type == TypeDonjon.OGRE) {
            l.addAll(donjon.getEntities(Donjon.OGRE.get(), zone(type), o -> !o.isRemoved()));
        } else {
            l.addAll(donjon.getEntitiesOfClass(BossDonjon.class, zone(type), o -> !o.isRemoved()));
        }
        return l;
    }

    /** Tous les monstres de ce donjon qui ne sont pas le boss (sbires, araignées, squelettes…). */
    private static List<Monster> monstres(ServerLevel donjon, AABB zone) {
        return donjon.getEntitiesOfClass(Monster.class, zone, m -> !m.isRemoved() && m.isAlive() && !(m instanceof Ogre) && !(m instanceof BossDonjon));
    }

    private static final Map<TypeDonjon, Set<String>> PEUPLEES = new EnumMap<>(TypeDonjon.class);

    private static Set<String> peuplees(TypeDonjon t) {
        return PEUPLEES.computeIfAbsent(t, k -> new HashSet<>());
    }

    /**
     * Un nouveau groupe entre dans ce donjon : il est de nouveau à faire (s'il est vide) : les sbires, les énigmes (et leurs
     * solutions), les cristaux, le coffre.
     */
    public static void nouveauGroupe(MinecraftServer server, TypeDonjon type) {
        DonjonData data = DonjonData.get(server);
        ServerLevel niveau = Donjon.niveau(server);
        if (niveau != null && joueurs(niveau, type).isEmpty()) {
            for (Monster m : monstres(niveau, zone(type))) {
                m.discard();
            }
            peuplees(type).clear();
            if (type == TypeDonjon.OGRE) {
                Enigmes.reinitialiser(niveau);
                Enigmes.coffre(niveau, new net.minecraft.core.BlockPos(59, DonjonBuilder.SOL + 1, 65));
            } else {
                Epreuves.de(type).reinitialiser(niveau, true);
            }
            Cristaux.restaurer(niveau, type);
        }
        if (data.vaincu(type)) {
            data.setVaincu(type, false);
            if (niveau != null) {
                for (ServerPlayer p : joueurs(niveau, type)) {
                    PacketDistributor.sendToPlayer(p, new Victoire(false));
                }
            }
        }
    }

    /** Retire le boss et tous les monstres de ce donjon (reconstruction, test). */
    public static void retirer(ServerLevel donjon, TypeDonjon type) {
        for (Mob o : boss(donjon, type)) {
            o.discard();
        }
        for (Monster m : monstres(donjon, zone(type))) {
            m.discard();
        }
        peuplees(type).clear();
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

    /** Un monstre de Minecraft (araignée, squelette…) posé pour un boss : il reste, même loin des joueurs. */
    static <T extends Mob> T poserMob(ServerLevel donjon, EntityType<T> type, double x, double y, double z) {
        T m = type.create(donjon);
        if (m == null) {
            return null;
        }
        m.moveTo(x, y, z, donjon.random.nextFloat() * 360, 0);
        m.finalizeSpawn(donjon, donjon.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null);
        m.setPersistenceRequired();
        donjon.addFreshEntity(m);
        return m;
    }

    /** Combien de monstres (hors boss) dans cette arène : jamais plus de 16 à la fois. */
    static int placeArene(ServerLevel donjon, Vec3 c) {
        return Math.max(0, 16 - monstres(donjon, new AABB(c, c).inflate(32, 15, 32)).size());
    }

    /** Ugrak appelle ses sbires : ils sortent de terre en cercle autour de l'arène (jamais plus de 16 à la fois). */
    static void invoquer(ServerLevel donjon, int gobelins, int brutes) {
        Vec3 c = DonjonBuilder.ARENE;
        int place = placeArene(donjon, c);
        Random r = new Random();
        double depart = r.nextDouble() * Math.PI * 2;
        int total = Math.min(place, gobelins + brutes);
        for (int i = 0; i < total; i++) {
            double a = depart + i * Math.PI * 2 / total;
            double d = 15 + r.nextDouble() * 3;
            poserSbire(donjon, i < brutes ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d, c);
        }
    }

    /** Une salle avant l'arène : son centre (au sol), son rayon, et qui la garde (gobelins, brutes). */
    private record Salle(String nom, Vec3 centre, double rayon, int gobelins, int brutes) {
    }

    private static final double H = DonjonBuilder.SOL + 1;
    private static final List<Salle> SALLES_OGRE = List.of(
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
    private static final Map<TypeDonjon, List<Salle>> SALLES = new EnumMap<>(TypeDonjon.class);

    private static List<Salle> salles(TypeDonjon type) {
        if (type == TypeDonjon.OGRE) {
            return SALLES_OGRE;
        }
        return SALLES.computeIfAbsent(type, t -> {
            PlanDonjon p = PlanDonjon.de(t);
            return List.of(
                    new Salle("carrefour", p.carrefour.centre(), 8, 5, 1),
                    new Salle("ordre", p.ordre.centre(), 7, 4, 1),
                    new Salle("code", p.code.centre(), 7, 4, 1),
                    new Salle("indice_ordre", p.indiceOrdre.centre(), 3, 3, 0),
                    new Salle("indice_code", p.indiceCode.centre(), 3, 3, 0),
                    new Salle("embuscade", p.embuscade.centre(), 3, 5, 0),
                    new Salle("coffre", p.coffre.centre(), 2, 0, 2),
                    new Salle("gardes", p.gardes.centre(), 10, 9, 3));
        });
    }

    private static void peupler(ServerLevel donjon, TypeDonjon type, List<ServerPlayer> joueurs) {
        Set<String> faites = peuplees(type);
        for (Salle s : salles(type)) {
            if (faites.contains(s.nom())) {
                continue;
            }
            boolean proche = joueurs.stream().anyMatch(p -> !p.isSpectator() && p.distanceToSqr(s.centre()) < 38 * 38);
            if (!proche || !chargee(donjon, s.centre())) {
                continue;
            }
            faites.add(s.nom());
            if (!sbires(donjon, new AABB(s.centre(), s.centre()).inflate(s.rayon() + 6, 10, s.rayon() + 6)).isEmpty()) {
                continue;
            }
            Random r = new Random();
            int n = s.gobelins() + s.brutes();
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * s.rayon();
                double x = s.centre().x + Math.cos(a) * d, z = s.centre().z + Math.sin(a) * d;
                double y = s.centre().y;
                if (s.nom().equals("escalier")) {
                    x = s.centre().x + (i - 1) * 1.6;
                    z = s.centre().z + (i % 2) * 1.5;
                }
                poserSbire(donjon, i < s.brutes() ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), x, y, z, new Vec3(type.x0() + 0.5, y, -20));
            }
        }
    }

    /** Pas deux apparitions d'un boss à moins de 30 s d'écart, quoi qu'il arrive. */
    private static final Map<TypeDonjon, Integer> PROCHAIN = new EnumMap<>(TypeDonjon.class);

    private static boolean chargee(ServerLevel donjon, Vec3 p) {
        return donjon.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.core.BlockPos.containing(p)));
    }

    private static Vec3 repos(TypeDonjon type) {
        return type == TypeDonjon.OGRE ? REPOS : PlanDonjon.de(type).repos();
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
        for (TypeDonjon type : TypeDonjon.values()) {
            List<ServerPlayer> joueurs = joueurs(donjon, type);
            if (joueurs.isEmpty() || data.vaincu(type)) {
                continue;
            }
            peupler(donjon, type, joueurs);
            Vec3 arene = type.arene();
            Vec3 repos = repos(type);
            boolean proche = joueurs.stream().anyMatch(p -> p.distanceToSqr(arene) < 75 * 75);
            if (proche && chargee(donjon, repos) && chargee(donjon, arene) && server.getTickCount() > PROCHAIN.getOrDefault(type, 0)
                    && boss(donjon, type).isEmpty()) {
                PROCHAIN.put(type, server.getTickCount() + 20 * 30);
                apparaitre(donjon, type);
            }
        }
    }

    static void apparaitre(ServerLevel donjon, TypeDonjon type) {
        EntityType<? extends Mob> t = switch (type) {
            case OGRE -> Donjon.OGRE.get();
            case GIVRE -> Donjon.COLOSSE.get();
            case ARAIGNEE -> Donjon.REINE.get();
            case LICHE -> Donjon.LICHE.get();
            case FOURMI -> Donjon.ROI_FOURMI.get();
        };
        Mob o = t.create(donjon);
        if (o == null) {
            return;
        }
        Vec3 r = repos(type);
        o.moveTo(r.x, r.y, r.z, 180F, 0F);
        o.yBodyRot = 180F;
        o.yHeadRot = 180F;
        o.finalizeSpawn(donjon, donjon.getCurrentDifficultyAt(o.blockPosition()), MobSpawnType.EVENT, null);
        o.setHealth(o.getMaxHealth());
        donjon.addFreshEntity(o);
    }

    /** Ugrak est mort. */
    static void victoire(ServerLevel donjon) {
        victoire(donjon, TypeDonjon.OGRE);
    }

    /** Le boss de ce donjon est mort : le donjon est terminé. */
    static void victoire(ServerLevel donjon, TypeDonjon type) {
        MinecraftServer server = donjon.getServer();
        DonjonData.get(server).setVaincu(type, true);
        if (type == TypeDonjon.OGRE) {
            Enigmes.finCombat(donjon, true);
        } else {
            Epreuves.de(type).finCombat(donjon, true);
        }
        // ses sbires s'effondrent avec lui
        Vec3 c = type.arene();
        for (Monster m : monstres(donjon, new AABB(c, c).inflate(34, 16, 34))) {
            if (m instanceof Sbire s) {
                s.surgir(donjon);
            }
            m.discard();
        }
        annoncer(donjon, type, "SYSTÈME", new String[] {"Donjon terminé ! " + type.boss + " est vaincu.", "Récompense : " + Economie.format(PRIME) + " chacun.",
                "Le portail de sortie est ouvert au milieu de l'arène.", "Le Système rachètera vos cristaux à la sortie."}, "", 0);
        for (ServerPlayer p : joueurs(donjon, type)) {
            PacketDistributor.sendToPlayer(p, new Victoire(true));
            Economie.crediter(server, p.getUUID(), PRIME);
            fr.cabane.pnj.Missions.evenement(p, "ugrak");
            p.sendSystemMessage(Component.literal("Donjon de rang S terminé (" + type.nom + ") : +" + Economie.format(PRIME)).withStyle(ChatFormatting.GOLD));
            donjon.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            ExperienceOrb.award(donjon, p.position(), 300);
        }
    }

    /** Le portail de sortie de l'arène, une fois le boss mort : y entrer ramène chacun chez soi. */
    static boolean dansSortieArene(ServerPlayer j) {
        TypeDonjon type = TypeDonjon.de(j.getX());
        if (!DonjonData.get(j.server).vaincu(type)) {
            return false;
        }
        Vec3 s = type.sortieArene();
        double dz = j.getZ() - s.z, dx = j.getX() - s.x, dy = j.getY() + 1 - s.y;
        return Math.abs(dz) < 0.8 && dx * dx + dy * dy < RAYON_SORTIE_ARENE * RAYON_SORTIE_ARENE;
    }

    /** Dire à ce joueur si le portail de l'arène de son donjon est ouvert. */
    static void prevenir(ServerPlayer j) {
        PacketDistributor.sendToPlayer(j, new Victoire(DonjonData.get(j.server).vaincu(TypeDonjon.de(j.getX()))));
    }

    @SubscribeEvent
    public static void changement(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer j && event.getTo() == Donjon.DIMENSION) {
            prevenir(j);
        }
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j && Donjon.dedans(j)) {
            prevenir(j);
        }
    }

    private Boss() {
    }
}
