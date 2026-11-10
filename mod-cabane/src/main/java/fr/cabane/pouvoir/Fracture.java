package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientFracture;
import io.netty.buffer.ByteBuf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * La fracture temporelle (susanodev seulement ; docs/susanodev/planche-fracture.png) : l'écran se fissure, puis les 10
 * dernières secondes se rembobinent en 2,5 s dans 10 blocs autour de lui : les blocs, la place et la vie des joueurs et des
 * mobs ; ce qui est apparu entre-temps disparaît, ce qui est mort revient. Les inventaires ne changent pas, et hors de la
 * zone rien ne bouge.
 *
 * <p>Tant qu'un joueur a susanodev, on retient ce qui se passe autour de lui : tous les changements de blocs (voir
 * mixin/LevelChunkMixin), et tous les deux ticks la place de ceux qui sont vivants. La fissure est dessinée par {@link ClientFracture}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Fracture {
    private static final double RAYON = 10, PORTEE = 48;
    /** En ticks : ce qu'on remonte, l'écart entre deux relevés, la fissure, le retour en arrière, la fissure qui se referme. */
    public static final int FENETRE = 200, CASSE = 14, LECTURE = 50, FIN = 10;
    private static final int ETAPE = 2, JOURNAL_MAX = 200_000;
    private static final DustParticleOptions VIOLET = new DustParticleOptions(new Vector3f(0.66F, 0.35F, 1F), 1.3F);

    /** Vérifié par le mixin à chaque bloc posé : quand personne n'a susanodev, ça ne coûte rien. */
    public static volatile boolean enregistre;
    private static boolean restauration;

    private record Changement(int tick, ResourceKey<Level> monde, BlockPos pos, BlockState avant, CompoundTag entite) {
    }

    private record Trace(int tick, double x, double y, double z, float lacet, float tangage, float vie, int feu) {
    }

    private record Mort(int tick, CompoundTag donnees) {
    }

    private static final ArrayDeque<Changement> JOURNAL = new ArrayDeque<>();
    private static final Map<UUID, ArrayDeque<Trace>> TRACES = new HashMap<>();
    private static final Map<UUID, Mort> MORTS = new HashMap<>();
    /** Ce qui est apparu récemment (monstre, flèche, objet par terre), et quand. */
    private static final Map<UUID, Integer> NES = new HashMap<>();
    private static final List<ServerPlayer> DEVS = new ArrayList<>();
    /** Ceux qui sont pris dans le retour en arrière : ils ne frappent pas et rien ne les blesse. */
    private static final Set<UUID> FIGES = new HashSet<>();
    private static Retour retour;

    private Fracture() {
    }

    /** Serveur → ceux qui sont dans la zone : la fissure sur l'écran. */
    public record Effet(int casse, int lecture, int fin) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("fracture_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Effet::casse, ByteBufCodecs.VAR_INT, Effet::lecture, ByteBufCodecs.VAR_INT, Effet::fin, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFracture.effet(p)));
    }

    // ================================================================ ce qu'on retient

    /** Appelé par le mixin juste avant qu'un bloc change. */
    public static void noter(LevelChunk chunk, BlockPos pos, BlockState apres) {
        if (restauration || !(chunk.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        BlockState avant = chunk.getBlockState(pos);
        if (avant == apres || !prochDunDev(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            return;
        }
        CompoundTag entite = null;
        if (avant.hasBlockEntity() && !avant.is(apres.getBlock())) {
            BlockEntity b = chunk.getBlockEntity(pos);
            if (b != null) {
                entite = b.saveWithFullMetadata(level.registryAccess());
            }
        }
        JOURNAL.addLast(new Changement(level.getServer().getTickCount(), level.dimension(), pos.immutable(), avant, entite));
        if (JOURNAL.size() > JOURNAL_MAX) {
            JOURNAL.pollFirst();
        }
    }

    private static boolean prochDunDev(Level level, double x, double y, double z) {
        for (ServerPlayer d : DEVS) {
            if (d.level() == level && d.distanceToSqr(x, y, z) < PORTEE * PORTEE) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer serveur = event.getServer();
        if (retour != null) {
            if (retour.avancer()) {
                retour = null;
                oublier();
            }
            return;
        }
        DEVS.clear();
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) {
            if (Susano3.DEV.equals(Pouvoirs.pouvoir(p))) {
                DEVS.add(p);
            }
        }
        boolean actif = !DEVS.isEmpty();
        if (enregistre && !actif) {
            oublier();
        }
        enregistre = actif;
        if (!actif) {
            return;
        }
        int maintenant = serveur.getTickCount();
        if (maintenant % ETAPE == 0) {
            Set<UUID> vus = new HashSet<>();
            for (ServerPlayer d : DEVS) {
                for (LivingEntity e : d.serverLevel().getEntitiesOfClass(LivingEntity.class, d.getBoundingBox().inflate(PORTEE))) {
                    if (vus.add(e.getUUID()) && !e.getTags().contains("cabane_pnj")) {
                        TRACES.computeIfAbsent(e.getUUID(), u -> new ArrayDeque<>()).addLast(trace(e, maintenant));
                    }
                }
            }
        }
        int limite = maintenant - FENETRE - ETAPE;
        while (!JOURNAL.isEmpty() && JOURNAL.peekFirst().tick() < limite) {
            JOURNAL.pollFirst();
        }
        for (Iterator<ArrayDeque<Trace>> i = TRACES.values().iterator(); i.hasNext(); ) {
            ArrayDeque<Trace> t = i.next();
            while (!t.isEmpty() && t.peekFirst().tick() < limite) {
                t.pollFirst();
            }
            if (t.isEmpty()) {
                i.remove();
            }
        }
        MORTS.values().removeIf(m -> m.tick() < limite);
        NES.values().removeIf(n -> n < limite);
    }

    private static Trace trace(LivingEntity e, int tick) {
        return new Trace(tick, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), e.getHealth(), e.getRemainingFireTicks());
    }

    private static void oublier() {
        JOURNAL.clear();
        TRACES.clear();
        MORTS.clear();
        NES.clear();
    }

    @SubscribeEvent
    public static void apparition(EntityJoinLevelEvent event) {
        if (enregistre && !restauration && !event.loadedFromDisk() && !(event.getEntity() instanceof Player) && event.getLevel() instanceof ServerLevel level) {
            NES.put(event.getEntity().getUUID(), level.getServer().getTickCount());
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (enregistre && !(e instanceof Player) && e.level() instanceof ServerLevel level && prochDunDev(level, e.getX(), e.getY(), e.getZ())) {
            CompoundTag donnees = new CompoundTag();
            if (e.save(donnees)) {
                MORTS.put(e.getUUID(), new Mort(level.getServer().getTickCount(), donnees));
            }
        }
    }

    // ================================================================ le retour en arrière

    /** La touche : l'écran se fissure et tout repart en arrière autour de lui. */
    static void lancer(ServerPlayer lanceur) {
        if (retour == null && lanceur.level().dimension() != Genjutsu.DIMENSION) {
            retour = new Retour(lanceur);
        }
    }

    private static final class Cible {
        final UUID id;
        LivingEntity entite;
        final Trace[] traces;
        boolean sansIaAvant;
        final Mort mort;

        Cible(UUID id, LivingEntity entite, Trace[] traces, Mort mort) {
            this.id = id;
            this.entite = entite;
            this.traces = traces;
            this.mort = mort;
            this.sansIaAvant = entite instanceof Mob m && m.isNoAi();
        }

        /** Le dernier relevé à ce moment-là (le plus ancien s'il n'y a rien d'aussi vieux). */
        Trace a(int curseur) {
            Trace choix = traces[0];
            for (Trace t : traces) {
                if (t.tick() > curseur) {
                    break;
                }
                choix = t;
            }
            return choix;
        }
    }

    private static final class Retour {
        final ServerLevel level;
        final Vec3 centre;
        final int depart;
        final List<Cible> cibles = new ArrayList<>();
        final List<Changement> blocs = new ArrayList<>();
        final Map<UUID, Integer> nes = new HashMap<>(NES);
        int indexBloc;
        int age;

        Retour(ServerPlayer lanceur) {
            level = lanceur.serverLevel();
            centre = lanceur.position();
            depart = level.getServer().getTickCount();
            double r2 = RAYON * RAYON;
            for (Changement c : JOURNAL) {
                if (c.monde() == level.dimension() && c.pos().distToCenterSqr(centre) <= r2) {
                    blocs.add(c);
                }
            }
            indexBloc = blocs.size() - 1;
            AABB zone = new AABB(centre, centre).inflate(RAYON);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, zone, x -> x.position().distanceToSqr(centre) <= r2 && !x.getTags().contains("cabane_pnj"))) {
                ArrayDeque<Trace> t = TRACES.get(e.getUUID());
                List<Trace> liste = new ArrayList<>(t == null ? List.of() : t);
                liste.add(trace(e, depart));
                ajouter(e.getUUID(), e, liste, null);
            }
            for (Map.Entry<UUID, Mort> m : MORTS.entrySet()) {
                ArrayDeque<Trace> t = TRACES.get(m.getKey());
                if (t != null && !t.isEmpty()) {
                    Trace derniere = t.peekLast();
                    if (Vec3.ZERO.add(derniere.x(), derniere.y(), derniere.z()).distanceToSqr(centre) <= r2) {
                        ajouter(m.getKey(), null, new ArrayList<>(t), m.getValue());
                    }
                }
            }
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 2F, 0.55F);
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 2F, 0.6F);
            for (ServerPlayer p : level.players()) {
                if (p.position().distanceToSqr(centre) <= r2) {
                    PacketDistributor.sendToPlayer(p, new Effet(CASSE, LECTURE, FIN));
                }
            }
        }

        private void ajouter(UUID id, LivingEntity e, List<Trace> traces, Mort mort) {
            Cible c = new Cible(id, e, traces.toArray(new Trace[0]), mort);
            cibles.add(c);
            FIGES.add(id);
            if (e instanceof Mob m) {
                m.setNoAi(true);
            }
        }

        /** @return vrai quand c'est fini */
        boolean avancer() {
            age++;
            if (age <= CASSE) {
                for (Cible c : cibles) {
                    if (c.entite != null && c.entite.isAlive()) {
                        placer(c.entite, c.traces[c.traces.length - 1]);
                    }
                }
                return false;
            }
            int lecture = age - CASSE;
            if (lecture == 1) {
                level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.6F, 0.6F);
            }
            if (lecture <= LECTURE) {
                int curseur = depart - lecture * FENETRE / LECTURE;
                remonter(curseur);
                for (int i = 0; i < 28; i++) {
                    double a = level.random.nextDouble() * Math.PI * 2, b = (level.random.nextDouble() - 0.5) * Math.PI;
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x + Math.cos(a) * Math.cos(b) * RAYON, centre.y + 1 + Math.sin(b) * RAYON,
                            centre.z + Math.sin(a) * Math.cos(b) * RAYON, 1, 0, 0, 0, 0);
                }
                return false;
            }
            if (lecture == LECTURE + 1) {
                remonter(depart - FENETRE - ETAPE);
                relacher();
                level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.6F, 1.7F);
            }
            return lecture > LECTURE + FIN;
        }

        private void remonter(int curseur) {
            restauration = true;
            try {
                // ce qui n'existait pas encore disparaît
                for (Iterator<Map.Entry<UUID, Integer>> i = nes.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry<UUID, Integer> n = i.next();
                    if (n.getValue() >= curseur) {
                        Entity e = level.getEntity(n.getKey());
                        if (e != null && !(e instanceof Player) && !e.getTags().contains("cabane_pnj") && e.position().distanceToSqr(centre) <= RAYON * RAYON) {
                            e.discard();
                        }
                        i.remove();
                    }
                }
                for (; indexBloc >= 0 && blocs.get(indexBloc).tick() >= curseur; indexBloc--) {
                    remettre(blocs.get(indexBloc));
                }
                for (Cible c : cibles) {
                    if (c.entite == null && c.mort != null && c.mort.tick() >= curseur) {
                        revivre(c, curseur);
                    }
                    if (c.entite != null && c.entite.isAlive() && !c.entite.isRemoved()) {
                        Trace t = c.a(curseur);
                        placer(c.entite, t);
                        trainee(c.entite, t);
                    }
                }
            } finally {
                restauration = false;
            }
        }

        /** La traînée violette, pour ceux qui le regardent (pas devant ses propres yeux). */
        private void trainee(LivingEntity e, Trace t) {
            for (ServerPlayer p : level.players()) {
                if (p != e) {
                    level.sendParticles(p, VIOLET, false, t.x(), t.y() + 1, t.z(), 4, 0.25, 0.5, 0.25, 0);
                    level.sendParticles(p, ParticleTypes.REVERSE_PORTAL, false, t.x(), t.y() + 1, t.z(), 3, 0.25, 0.5, 0.25, 0.01);
                }
            }
        }

        private void remettre(Changement c) {
            level.setBlock(c.pos(), c.avant(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (c.entite() != null) {
                BlockEntity b = level.getBlockEntity(c.pos());
                if (b != null) {
                    b.loadWithComponents(c.entite(), level.registryAccess());
                    b.setChanged();
                }
            }
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.pos().getX() + 0.5, c.pos().getY() + 0.5, c.pos().getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.02);
        }

        private void revivre(Cible c, int curseur) {
            Entity ancien = level.getEntity(c.id);
            if (ancien != null) {
                ancien.discard();
            }
            Trace t = c.a(curseur);
            CompoundTag d = c.mort.donnees().copy();
            d.putFloat("Health", Math.max(1F, t.vie()));
            d.putShort("DeathTime", (short) 0);
            d.putShort("HurtTime", (short) 0);
            d.putShort("Fire", (short) 0);
            Entity e = EntityType.create(d, level).orElse(null);
            if (e instanceof LivingEntity v) {
                v.moveTo(t.x(), t.y(), t.z(), t.lacet(), t.tangage());
                if (level.addFreshEntity(v)) {
                    c.entite = v;
                    if (v instanceof Mob m) {
                        c.sansIaAvant = m.isNoAi();
                        m.setNoAi(true);
                    }
                }
            }
        }

        private void placer(LivingEntity e, Trace t) {
            if (e instanceof ServerPlayer p) {
                p.connection.teleport(t.x(), t.y(), t.z(), t.lacet(), t.tangage());
                p.fallDistance = 0;
            } else {
                e.moveTo(t.x(), t.y(), t.z(), t.lacet(), t.tangage());
                e.setYHeadRot(t.lacet());
                e.yBodyRot = t.lacet();
            }
            e.setDeltaMovement(Vec3.ZERO);
            e.setHealth(Math.min(e.getMaxHealth(), t.vie()));
            e.setRemainingFireTicks(t.feu());
        }

        void relacher() {
            for (Cible c : cibles) {
                FIGES.remove(c.id);
                if (c.entite instanceof Mob m && !c.sansIaAvant) {
                    m.setNoAi(false);
                }
            }
            FIGES.clear();
        }
    }

    // ================================================================ pendant ce temps-là, personne ne bouge

    @SubscribeEvent
    public static void intouchable(LivingIncomingDamageEvent event) {
        if (!FIGES.isEmpty() && FIGES.contains(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void frapper(AttackEntityEvent event) {
        if (!FIGES.isEmpty() && FIGES.contains(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void clic(PlayerInteractEvent.RightClickBlock event) {
        if (!FIGES.isEmpty() && FIGES.contains(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        if (retour != null) {
            retour.relacher();
            retour = null;
        }
        enregistre = false;
        DEVS.clear();
        oublier();
    }
}
