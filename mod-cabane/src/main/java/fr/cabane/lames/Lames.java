package fr.cabane.lames;

import com.mojang.serialization.Codec;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les sept lames de la Brume (voir docs/lames-brume) : des objets que l'hôte donne lui-même (/give), jamais dans un
 * coffre ni sur un monstre. Chacune frappe à sa façon (les gestes sont côté client, {@code ClientLames}) et a son coup
 * spécial au clic droit ; quand on ne la tient pas, elle se range dans le dos (ou sur le côté). Ici : les objets, ce qui
 * vole (la Kubikiribôchô lancée, la grande lame de chakra), les paquets, et ce qui dure plusieurs ticks (la Samehada
 * qui garde quelqu'un dedans, le saut de Kabutowari, le fil de Nuibari, les explosions de Shibuki).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Lames {
    /**
     * Les sept lames : dégâts, coups par seconde, portée en plus, recharge du coup spécial (en ticks). Toutes frappent
     * pareil (comme une épée en diamant Tranchant IV : 9,5), c'est la façon de frapper qui change (les combos).
     */
    /** Les dégâts de chaque coup, pour toutes les lames : une épée en diamant (7) avec Tranchant IV (+2,5). */
    public static final float DEGATS = 9.5F;

    public enum Sorte {
        KUBIKIRIBOCHO("kubikiribocho", DEGATS, 1.6F, 1, 160),
        SAMEHADA("samehada", DEGATS, 1.6F, 0.5, 300),
        KIBA("kiba", DEGATS, 1.6F, 0, 200),
        NUIBARI("nuibari", DEGATS, 1.6F, 2, 240),
        SHIBUKI("shibuki", DEGATS, 1.6F, 0.5, 240),
        KABUTOWARI("kabutowari", DEGATS, 1.6F, 0.5, 300),
        HIRAMEKAREI("hiramekarei", DEGATS, 1.6F, 0.5, 300);

        public final String id;
        public final float degats, vitesse;
        public final double portee;
        public final int recharge;

        Sorte(String id, float degats, float vitesse, double portee, int recharge) {
            this.id = id;
            this.degats = degats;
            this.vitesse = vitesse;
            this.portee = portee;
            this.recharge = recharge;
        }

        public int bit() {
            return 1 << ordinal();
        }
    }

    // ------------------------------------------------------------------ les objets, l'état dans l'objet
    private static final DeferredRegister.DataComponents COMPOSANTS = DeferredRegister.createDataComponents(Cabane.MODID);
    /** La Kubikiribôchô est en vol depuis ce tick (absent : dans la main). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> LANCEE =
            COMPOSANTS.registerComponentType("lame_lancee", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** La Samehada a quelqu'un dedans depuis ce tick (absent : vide). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> AVALE =
            COMPOSANTS.registerComponentType("lame_avale", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    private static final Map<Sorte, DeferredItem<LameItem>> OBJETS = new HashMap<>();

    static {
        for (Sorte s : Sorte.values()) {
            OBJETS.put(s, CabaneItems.ITEMS.register(s.id, () -> new LameItem(s, new net.minecraft.world.item.Item.Properties()
                    .stacksTo(1).rarity(Rarity.EPIC).fireResistant())));
        }
    }

    public static LameItem objet(Sorte s) {
        return OBJETS.get(s).get();
    }

    /** La sorte de lame de cet objet (null : pas une lame). */
    public static Sorte sorte(ItemStack stack) {
        return stack.getItem() instanceof LameItem l ? l.sorte : null;
    }

    // ------------------------------------------------------------------ ce qui vole
    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<LameLancee>> LANCER = ENTITES.register("lame_lancee",
            () -> EntityType.Builder.<LameLancee>of(LameLancee::new, MobCategory.MISC).sized(1.6F, 0.4F).clientTrackingRange(8)
                    .updateInterval(1).build("lame_lancee"));
    public static final DeferredHolder<EntityType<?>, EntityType<LameChakra>> CHAKRA = ENTITES.register("lame_chakra",
            () -> EntityType.Builder.<LameChakra>of(LameChakra::new, MobCategory.MISC).sized(0.5F, 0.5F).clientTrackingRange(8)
                    .updateInterval(1).build("lame_chakra"));

    public static void register(IEventBus modBus) {
        COMPOSANTS.register(modBus);
        ENTITES.register(modBus);
        modBus.addListener(Lames::paquets);
    }

    // ------------------------------------------------------------------ les paquets

    /** Les lames qu'un joueur a sur lui (un bit par sorte) : les autres les voient dans son dos. */
    public record Portees(int entite, int masque) implements CustomPacketPayload {
        public static final Type<Portees> TYPE = new Type<>(Cabane.id("lames_portees"));
        public static final StreamCodec<ByteBuf, Portees> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Portees::entite,
                ByteBufCodecs.VAR_INT, Portees::masque, Portees::new);

        @Override
        public Type<Portees> type() {
            return TYPE;
        }
    }

    /** Quelqu'un est (ou n'est plus) dans la Samehada : on ne le dessine plus. */
    public record Avale(int entite, boolean dedans) implements CustomPacketPayload {
        public static final Type<Avale> TYPE = new Type<>(Cabane.id("lames_avale"));
        public static final StreamCodec<ByteBuf, Avale> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Avale::entite,
                ByteBufCodecs.BOOL, Avale::dedans, Avale::new);

        @Override
        public Type<Avale> type() {
            return TYPE;
        }
    }

    /** Ce joueur lance le clic droit de sa lame : tout le monde joue son animation (depuis ce tick). */
    public record Special(int entite, int sorte, int depuis) implements CustomPacketPayload {
        public static final Type<Special> TYPE = new Type<>(Cabane.id("lames_special"));
        public static final StreamCodec<ByteBuf, Special> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Special::entite,
                ByteBufCodecs.VAR_INT, Special::sorte, ByteBufCodecs.VAR_INT, Special::depuis, Special::new);

        @Override
        public Type<Special> type() {
            return TYPE;
        }
    }

    /** Le client : son joueur commence ce coup du combo (le serveur frappe la zone du coup, {@link Zones}). */
    public record Coup(int index) implements CustomPacketPayload {
        public static final Type<Coup> TYPE = new Type<>(Cabane.id("lames_coup"));
        public static final StreamCodec<ByteBuf, Coup> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Coup::index, Coup::new);

        @Override
        public Type<Coup> type() {
            return TYPE;
        }
    }

    static void annoncer(ServerPlayer j, Sorte s, int depuis) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(j, new Special(j.getId(), s.ordinal(), depuis));
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Portees.TYPE, Portees.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientLames.portees(p)))
                .playToClient(Avale.TYPE, Avale.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientLames.avale(p)))
                .playToClient(Special.TYPE, Special.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientLames.special(p)))
                .playToServer(Coup.TYPE, Coup.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer j) {
                        Zones.joue(j, p.index());
                    }
                }));
    }

    // ------------------------------------------------------------------ dans le dos : qui porte quoi

    private static final Map<UUID, Integer> MASQUES = new HashMap<>();

    static int masque(Player joueur) {
        int m = 0;
        var inv = joueur.getInventory();
        for (List<ItemStack> l : List.of(inv.items, inv.offhand)) {
            for (ItemStack s : l) {
                if (s.getItem() instanceof LameItem lame && !s.has(LANCEE.get())) {
                    m |= lame.sorte.bit();
                }
            }
        }
        return m;
    }

    @SubscribeEvent
    public static void suivre(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer porteur && event.getEntity() instanceof ServerPlayer voit) {
            PacketDistributor.sendToPlayer(voit, new Portees(porteur.getId(), masque(porteur)));
            if (DEDANS.containsKey(porteur.getId())) {
                PacketDistributor.sendToPlayer(voit, new Avale(porteur.getId(), true));
            }
        }
    }

    // ------------------------------------------------------------------ la Samehada : quelqu'un dedans

    /** Qui est dans la Samehada de qui, et jusqu'à quand. */
    record Dedans(ServerPlayer porteur, LivingEntity proie, int debut, int fin) {
    }

    private static final Map<UUID, Dedans> AVALES = new HashMap<>();
    /** L'identifiant des proies avalées, vers leur porteur (pour les empêcher d'agir). */
    private static final Map<Integer, UUID> DEDANS = new HashMap<>();
    public static final int DUREE_AVALE = 200;

    /** Cette créature est dans une Samehada. */
    static boolean avale(LivingEntity e) {
        return DEDANS.containsKey(e.getId());
    }

    static boolean aQuelquUn(ServerPlayer porteur) {
        return AVALES.containsKey(porteur.getUUID());
    }

    static boolean avaler(ServerPlayer porteur, LivingEntity proie, ItemStack lame) {
        if (proie == porteur || DEDANS.containsKey(proie.getId()) || AVALES.containsKey(porteur.getUUID())
                || proie.getMaxHealth() > 120 || (proie instanceof Player p && (p.isCreative() || p.isSpectator()))) {
            return false;
        }
        ServerLevel level = porteur.serverLevel();
        int t = (int) level.getGameTime();
        AVALES.put(porteur.getUUID(), new Dedans(porteur, proie, t, t + DUREE_AVALE));
        DEDANS.put(proie.getId(), porteur.getUUID());
        lame.set(AVALE.get(), t);
        proie.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DUREE_AVALE + 20, 0, false, false));
        proie.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DUREE_AVALE + 20, 0, false, false));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(proie, new Avale(proie.getId(), true));
        level.playSound(null, porteur.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1.2F, 0.5F);
        level.playSound(null, proie.blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.PLAYERS, 1F, 0.7F);
        return true;
    }

    /** Elle le recrache devant elle, et la Samehada redevient normale. */
    static void recracher(ServerPlayer porteur) {
        Dedans d = AVALES.remove(porteur.getUUID());
        if (d == null) {
            return;
        }
        DEDANS.remove(d.proie().getId());
        for (ItemStack s : porteur.getInventory().items) {
            s.remove(AVALE.get());
        }
        porteur.getOffhandItem().remove(AVALE.get());
        LivingEntity proie = d.proie();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(proie, new Avale(proie.getId(), false));
        proie.removeEffect(MobEffects.BLINDNESS);
        proie.removeEffect(MobEffects.DARKNESS);
        if (proie.isAlive() && proie.level() == porteur.level()) {
            Vec3 vue = porteur.getLookAngle();
            Vec3 ou = porteur.getEyePosition().add(vue.x * 1.6, -0.6, vue.z * 1.6);
            proie.teleportTo(ou.x, ou.y, ou.z);
            proie.setDeltaMovement(vue.x * 1.3, 0.45, vue.z * 1.3);
            proie.hurtMarked = true;
            ServerLevel level = porteur.serverLevel();
            level.sendParticles(ParticleTypes.SPLASH, ou.x, ou.y + 0.8, ou.z, 30, 0.4, 0.4, 0.4, 0.2);
            level.playSound(null, porteur.blockPosition(), SoundEvents.LLAMA_SPIT, SoundSource.PLAYERS, 1.4F, 0.6F);
        }
        porteur.getCooldowns().addCooldown(objet(Sorte.SAMEHADA), Sorte.SAMEHADA.recharge);
    }

    /** Une proie avalée ne frappe personne, ne touche à rien. */
    @SubscribeEvent
    public static void frappeDedans(LivingIncomingDamageEvent event) {
        Entity source = event.getSource().getEntity();
        if (source != null && DEDANS.containsKey(source.getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void attaqueDedans(AttackEntityEvent event) {
        if (DEDANS.containsKey(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void objetDedans(PlayerInteractEvent.RightClickItem event) {
        if (DEDANS.containsKey(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void blocDedans(PlayerInteractEvent.RightClickBlock event) {
        if (DEDANS.containsKey(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void casseDedans(PlayerInteractEvent.LeftClickBlock event) {
        if (DEDANS.containsKey(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void entiteDedans(PlayerInteractEvent.EntityInteract event) {
        if (DEDANS.containsKey(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ plus tard (au bon moment de l'animation)

    record Tache(ServerPlayer joueur, Sorte sorte, int quand, Runnable quoi) {
    }

    private static final List<Tache> TACHES = new ArrayList<>();

    /** Dans {@code ticks} ticks, si le joueur est toujours là et tient toujours cette lame. */
    static void plusTard(ServerPlayer j, Sorte s, int ticks, Runnable quoi) {
        TACHES.add(new Tache(j, s, j.getServer().getTickCount() + ticks, quoi));
    }

    // ------------------------------------------------------------------ Kabutowari : le saut, puis l'écrasement

    record Saut(ServerPlayer joueur, int debut) {
    }

    private static final Map<UUID, Saut> SAUTS = new HashMap<>();

    /** Le saut : il s'accroupit (5 ticks), s'envole, puis plonge (au tick 12) ; déjà en l'air, il plonge tout de suite. */
    static void sauter(ServerPlayer joueur) {
        int debut = joueur.onGround() ? joueur.tickCount : joueur.tickCount - 11;
        SAUTS.put(joueur.getUUID(), new Saut(joueur, debut));
        joueur.serverLevel().playSound(null, joueur.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.7F, 1.3F);
    }

    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (SAUTS.containsKey(event.getEntity().getUUID())) {
            event.setDamageMultiplier(0);
        }
    }

    // ------------------------------------------------------------------ Nuibari : le fil ; Shibuki : les explosions

    record Fil(ServerPlayer joueur, List<LivingEntity> cousus, Vec3 bout, int fin) {
    }

    record Boom(ServerLevel level, ServerPlayer joueur, Vec3 ou, float rayon, float degats, int quand) {
    }

    private static final List<Fil> FILS = new ArrayList<>();
    private static final List<Boom> BOOMS = new ArrayList<>();

    static void coudre(ServerPlayer joueur, List<LivingEntity> cousus, Vec3 bout) {
        FILS.add(new Fil(joueur, cousus, bout, joueur.tickCount + 34));
    }

    static void exploser(ServerPlayer joueur, Vec3 ou, float rayon, float degats, int dans) {
        BOOMS.add(new Boom(joueur.serverLevel(), joueur, ou, rayon, degats, joueur.getServer().getTickCount() + dans));
    }

    /** Une explosion qui ne casse rien et ne touche pas celui qui l'a lancée. */
    static void boom(ServerLevel level, ServerPlayer joueur, Vec3 ou, float rayon, float degats) {
        level.sendParticles(rayon > 1.5F ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, ou.x, ou.y, ou.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.FLAME, ou.x, ou.y, ou.z, 30, rayon * 0.4, rayon * 0.3, rayon * 0.4, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, ou.x, ou.y + 0.5, ou.z, 14, rayon * 0.4, rayon * 0.3, rayon * 0.4, 0.03);
        level.playSound(null, ou.x, ou.y, ou.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1F, 0.9F + level.random.nextFloat() * 0.2F);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(ou, ou).inflate(rayon))) {
            if (e == joueur || !e.isAlive() || e.distanceToSqr(ou) > rayon * rayon * 1.2) {
                continue;
            }
            e.hurt(level.damageSources().explosion(joueur, joueur), degats * Zones.force(joueur));
            Vec3 loin = e.position().subtract(ou).normalize();
            e.setDeltaMovement(e.getDeltaMovement().add(loin.x * 0.7, 0.45, loin.z * 0.7));
            e.hurtMarked = true;
        }
    }

    // ------------------------------------------------------------------ chaque tick

    private static final DustParticleOptions FIL = new DustParticleOptions(new org.joml.Vector3f(0.95F, 0.95F, 1F), 0.5F);

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        // la Samehada : la proie reste dedans (on la porte), elle perd de la vie et le porteur la gagne
        for (Iterator<Dedans> it = new ArrayList<>(AVALES.values()).iterator(); it.hasNext(); ) {
            Dedans d = it.next();
            ServerPlayer p = d.porteur();
            LivingEntity proie = d.proie();
            if (p.isRemoved() || !p.isAlive() || !proie.isAlive() || proie.isRemoved() || proie.level() != p.level()
                    || p.serverLevel().getGameTime() >= d.fin()) {
                recracher(p);
                continue;
            }
            proie.teleportTo(p.getX(), p.getY() + 0.2, p.getZ());
            proie.setDeltaMovement(Vec3.ZERO);
            proie.fallDistance = 0;
            long age = p.serverLevel().getGameTime() - d.debut();
            if (age > 0 && age % 40 == 0) {
                proie.hurt(p.serverLevel().damageSources().indirectMagic(p, p), 2);
                p.heal(2);
                p.serverLevel().sendParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 1.2, p.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
            }
        }
        // les effets en attente (au bon moment de l'animation)
        int tick = server.getTickCount();
        for (Tache t : new ArrayList<>(TACHES)) {
            if (tick < t.quand()) {
                continue;
            }
            TACHES.remove(t);
            ServerPlayer j = t.joueur();
            if (!j.isRemoved() && j.isAlive() && sorte(j.getMainHandItem()) == t.sorte()) {
                t.quoi().run();
            }
        }
        // Kabutowari : à la réception, tout autour s'écrase
        for (Iterator<Saut> it = SAUTS.values().iterator(); it.hasNext(); ) {
            Saut s = it.next();
            ServerPlayer j = s.joueur();
            int age = j.tickCount - s.debut();
            if (j.isRemoved() || age > 80) {
                it.remove();
            } else if (age == 5) {
                Vec3 vue = j.getLookAngle();
                j.setDeltaMovement(vue.x * 0.45, 0.8, vue.z * 0.45);
                j.hurtMarked = true;
            } else if (age == 12) {
                Vec3 vue = j.getLookAngle();
                j.setDeltaMovement(vue.x * 0.25, -1.5, vue.z * 0.25);
                j.hurtMarked = true;
            } else if (age > 12 && j.onGround()) {
                it.remove();
                LameItem.ecraser(j);
            }
        }
        // Nuibari : le fil, de la main à l'aiguille en passant par chaque cousu
        for (Iterator<Fil> it = FILS.iterator(); it.hasNext(); ) {
            Fil f = it.next();
            ServerPlayer j = f.joueur();
            if (j.isRemoved() || j.tickCount > f.fin()) {
                it.remove();
                continue;
            }
            List<Vec3> pts = new ArrayList<>();
            pts.add(j.getEyePosition().add(0, -0.45, 0));
            for (LivingEntity e : f.cousus()) {
                if (e.isAlive()) {
                    pts.add(e.position().add(0, e.getBbHeight() * 0.6, 0));
                }
            }
            pts.add(f.bout());
            for (int k = 0; k + 1 < pts.size(); k++) {
                Vec3 a = pts.get(k), b = pts.get(k + 1);
                int n = (int) Math.ceil(a.distanceTo(b) / 0.25);
                for (int q = 0; q <= n; q++) {
                    Vec3 p = a.lerp(b, q / (double) n);
                    j.serverLevel().sendParticles(FIL, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
        }
        // Shibuki : les explosions, l'une après l'autre
        int maintenant = server.getTickCount();
        for (Iterator<Boom> it = BOOMS.iterator(); it.hasNext(); ) {
            Boom b = it.next();
            if (maintenant >= b.quand()) {
                it.remove();
                boom(b.level(), b.joueur(), b.ou(), b.rayon(), b.degats());
            }
        }
        // qui porte quoi (deux fois par seconde)
        if (maintenant % 10 == 0) {
            for (ServerPlayer j : server.getPlayerList().getPlayers()) {
                int m = masque(j);
                Integer avant = MASQUES.put(j.getUUID(), m);
                if (avant == null || avant != m) {
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(j, new Portees(j.getId(), m));
                }
            }
        }
    }

    @SubscribeEvent
    public static void depart(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            recracher(j);
            UUID porteur = DEDANS.get(j.getId());
            if (porteur != null) {
                Dedans d = AVALES.get(porteur);
                if (d != null) {
                    recracher(d.porteur());
                }
            }
            MASQUES.remove(j.getUUID());
            SAUTS.remove(j.getUUID());
        }
    }

    @SubscribeEvent
    public static void changeMonde(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            recracher(j);
        }
    }

    private Lames() {
    }
}
