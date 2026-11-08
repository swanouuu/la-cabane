package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientKaguya;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.joml.Vector3f;

/**
 * Le pouvoir Kaguya (le clan de Kimimaro : elle fait pousser ses os et s'en sert comme armes), d'après la planche validée
 * (docs/kaguya). Trois listes blanches : « kaguya » (niveau 1), « kaguya2 » (jusqu'au 2), « kaguya3 » (jusqu'au 3).
 * À tous les niveaux : six os dans le dos, torse nu (le bas de la marque maudite), Résistance I, et ceux qui
 * la frappent au corps à corps se piquent sur ses os (comme l'enchantement Épines).
 * <ul>
 * <li>niveau 1 : poings d'os (7 à mains nues) ; clic droit : les phalanges, 5 balles d'os (2 chacune, 20 blocs, 5 s) ;
 * accroupi : une ligne de piques sort du sol vers la cible (10 blocs, 8 et en l'air, 15 s) ;</li>
 * <li>niveau 2 : elle s'arrache la colonne, qui devient l'épée colonne, bloquée dans le slot 1 (11, portée +1) ; clic
 * droit : le fouet (10 blocs, ramène la cible, 6, 8 s) ; accroupi : la danse du saule (10 autour, 12 s) ; technique : les
 * lances pendant 30 s (l'épée rentre ; coups qui traversent l'armure ; clic droit : elle fonce 8 blocs ; 60 s) ;</li>
 * <li>niveau 3 : la peau de la marque maudite, une queue (6 à ceux qui sont derrière, toutes les 2 s), l'épée à 14 ;
 * clic droit : le grand fouet (12 à tous à 14 blocs, à terre, 10 s) ; accroupi : la forêt d'os (16 et en l'air à 15
 * blocs, les piques bloquent 6 s, 25 s) ; technique : la pluie de pieux (6 os du dos, 6 chacun, 25 blocs, 20 s) ; accroupi
 * + technique : les grosses lances ; J : le dinosaure d'os ({@link KaguyaDino}).</li>
 * </ul>
 * Ce qu'on voit est dessiné par {@link ClientKaguya} ; rien ne casse.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Kaguya {
    public static final String NOM = "kaguya";
    public static final String NOM2 = "kaguya2";
    public static final String NOM3 = "kaguya3";

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    // ================================================================ les chiffres (en ticks)

    public static final int RECHARGE_PHALANGES = 5 * 20, RECHARGE_PIQUES = 15 * 20, RECHARGE_FOUET = 8 * 20, RECHARGE_SAULE = 12 * 20,
            RECHARGE_LANCES = 60 * 20, DUREE_LANCES = 30 * 20, RECHARGE_FONCE = 3 * 20, RECHARGE_GRAND_FOUET = 10 * 20, RECHARGE_FORET = 25 * 20,
            DUREE_FORET = 6 * 20, RECHARGE_PIEUX = 20 * 20, REPOUSSE_PIEUX = 5 * 20;
    /** L'épée colonne arrive dans sa main 1,2 s après le début de l'éveil 2 (elle tire d'abord sa colonne). */
    public static final int EPEE_PRETE = 24;
    private static final float POINGS = 7, EPEE_2 = 11, EPEE_3 = 14, LANCES_2 = 6, LANCES_3 = 8;
    private static final float BALLE = 2, PIQUES = 8, FOUET = 6, SAULE = 10, FONCE = 12, GRAND_FOUET = 12, FORET = 16, PIEU = 6, QUEUE = 6;
    public static final double PORTEE_BALLES = 20, VITESSE_BALLE = 2.5, PORTEE_PIQUES = 10, PORTEE_FOUET = 10, RAYON_SAULE = 4,
            RAYON_GRAND_FOUET = 14, RAYON_FORET = 15, PORTEE_PIEUX = 25;
    /** Les pieux montent au-dessus d'elle, puis plongent (chacun un peu après l'autre). */
    public static final int PIEU_MONTE = 12, PIEU_ECART = 3, PIEU_PLONGE = 6;

    // ================================================================ l'épée colonne

    public static final ResourceLocation EPEE = Cabane.id("epee_colonne");
    private static final ResourceLocation PORTEE_EPEE = Cabane.id("epee_colonne_portee");

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        event.register(Registries.ITEM, EPEE, Epee::new);
    }

    public static Item epee() {
        return BuiltInRegistries.ITEM.get(EPEE);
    }

    /** Comme une épée (1,6 coup par seconde), un bloc de portée en plus ; ses dégâts viennent du pouvoir (11, puis 14). */
    private static final class Epee extends Item {
        Epee() {
            super(new Item.Properties().stacksTo(1).attributes(ItemAttributeModifiers.builder()
                    .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, EPEE_2 - 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(PORTEE_EPEE, 1.5, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .build()));
        }

        /** Chez quelqu'un qui n'est pas une Kaguya éveillée au niveau 2 (ou qui l'a récupérée), elle s'efface. */
        @Override
        public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean tenue) {
            if (!level.isClientSide && entity instanceof ServerPlayer p && (niveau(p) < 2 || LANCES.containsKey(p.getUUID()))) {
                stack.setCount(0);
            }
        }

        /** Lâchée par terre, elle tombe en poussière (et revient dans sa main). */
        @Override
        public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entite) {
            if (!entite.level().isClientSide) {
                entite.discard();
            }
            return true;
        }
    }

    static boolean tientEpee(ServerPlayer joueur) {
        return joueur.getMainHandItem().is(epee());
    }

    /** Ce qu'il y avait dans son slot 1 quand l'épée y est arrivée (sac plein : gardé de côté, rendu quand elle s'endort). */
    private static final Map<UUID, ItemStack> RANGES = new HashMap<>();

    /** L'épée dans le slot 1, et la barre d'objets dessus : elle ne peut ni la lâcher, ni prendre autre chose. */
    private static void bloquerEpee(ServerPlayer joueur) {
        Inventory inv = joueur.getInventory();
        int ou = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(epee())) {
                if (ou < 0) {
                    ou = i;
                } else {
                    inv.items.set(i, ItemStack.EMPTY);
                }
            }
        }
        if (joueur.containerMenu.getCarried().is(epee())) {
            joueur.containerMenu.setCarried(ItemStack.EMPTY);
        }
        if (ou != 0) {
            ItemStack avant = inv.items.get(0);
            ItemStack epee = ou > 0 ? inv.items.get(ou) : new ItemStack(epee());
            if (ou > 0) {
                inv.items.set(ou, avant);
            } else if (!avant.isEmpty()) {
                inv.items.set(0, ItemStack.EMPTY);
                if (!inv.add(avant)) {
                    ItemStack deja = RANGES.get(joueur.getUUID());
                    if (deja == null || deja.isEmpty()) {
                        RANGES.put(joueur.getUUID(), avant);
                    } else {
                        joueur.drop(avant, false);
                    }
                }
            }
            inv.items.set(0, epee);
            if (ou < 0) {
                son(joueur.serverLevel(), joueur.position(), SoundEvents.BONE_BLOCK_PLACE, 1.2F, 0.6F);
            }
        }
        if (inv.selected != 0) {
            inv.selected = 0;
            joueur.connection.send(new ClientboundSetCarriedItemPacket(0));
        }
    }

    /** L'épée s'en va (elle s'endort, ou les lances poussent) ; endormie, elle retrouve ce qu'elle avait mis de côté. */
    private static void retirerEpee(ServerPlayer joueur, boolean rendre) {
        Inventory inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(epee())) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (joueur.containerMenu.getCarried().is(epee())) {
            joueur.containerMenu.setCarried(ItemStack.EMPTY);
        }
        if (rendre) {
            ItemStack avant = RANGES.remove(joueur.getUUID());
            if (avant != null && !avant.isEmpty() && !inv.add(avant)) {
                joueur.drop(avant, false);
            }
        }
    }

    // ================================================================ qui est éveillée

    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    /** Quand chaque niveau a commencé (ticks du serveur) : l'épée arrive un peu après le début du niveau 2. */
    private static final Map<UUID, Integer> DEBUT_2 = new HashMap<>();
    private static final Map<UUID, Integer> PRET_DROIT = new HashMap<>(), PRET_ACCROUPI = new HashMap<>(), PRET_TECH = new HashMap<>(),
            PRET_LANCES = new HashMap<>(), PRET_FONCE = new HashMap<>();
    /** Les lances : jusqu'à quand, et si ce sont les grosses (niveau 3). */
    static final Map<UUID, int[]> LANCES = new HashMap<>();
    private static final ResourceLocation VITESSE_LANCES = Cabane.id("kaguya_lances");
    private static boolean dansPouvoir;

    static boolean eveillee(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    public static int niveau(ServerPlayer joueur) {
        return eveillee(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    private static boolean pret(Map<UUID, Integer> m, ServerPlayer joueur) {
        return joueur.server.getTickCount() >= m.getOrDefault(joueur.getUUID(), 0);
    }

    private static void armer(Map<UUID, Integer> m, ServerPlayer joueur, int ticks) {
        m.put(joueur.getUUID(), joueur.server.getTickCount() + ticks);
    }

    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        ServerLevel monde = joueur.serverLevel();
        Vec3 p = joueur.position().add(0, 1.2, 0);
        son(monde, p, SoundEvents.BONE_BLOCK_BREAK, 1.6F, niveau >= 3 ? 0.5F : 0.7F);
        son(monde, p, SoundEvents.SKELETON_HURT, 0.8F, 0.6F);
        if (niveau == 2) {
            DEBUT_2.put(joueur.getUUID(), joueur.server.getTickCount());
        }
        if (niveau >= 3) {
            // la transformation fait rentrer les lances du niveau 2
            finirLances(joueur, true);
            son(monde, p, SoundEvents.WITHER_SKELETON_AMBIENT, 1.2F, 0.6F);
            KaguyaDino.nouvelEveil(joueur);
        }
        resistance(joueur, niveau);
    }

    static void endormir(ServerPlayer joueur) {
        Integer etait = EVEILLES.remove(joueur.getUUID());
        KaguyaDino.arreter(joueur);
        finirLances(joueur, false);
        retirerEpee(joueur, true);
        DEBUT_2.remove(joueur.getUUID());
        PRET_DROIT.remove(joueur.getUUID());
        PRET_ACCROUPI.remove(joueur.getUUID());
        PRET_TECH.remove(joueur.getUUID());
        PRET_LANCES.remove(joueur.getUUID());
        PRET_FONCE.remove(joueur.getUUID());
        CHARGES.remove(joueur.getUUID());
        if (etait != null) {
            joueur.removeEffect(MobEffects.DAMAGE_RESISTANCE);
            if (joueur.isAlive()) {
                // les os rentrent dans son corps
                joueur.serverLevel().sendParticles(OS_PART, joueur.getX(), joueur.getY() + 1.2, joueur.getZ(), 30, 0.4, 0.5, 0.4, 0.1);
                son(joueur.serverLevel(), joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 1F, 1.2F);
            }
        }
    }

    /** Ses os durs comme l'acier : Résistance I (sans particules ; plus haut, elle ne prenait presque plus rien). */
    private static void resistance(ServerPlayer joueur, int niveau) {
        if (niveau > 0) {
            joueur.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 50, 0, false, false, true));
        }
    }

    // ================================================================ réseau

    public static final int DROIT = 0, ACCROUPI = 1, TECHNIQUE = 2, LANCES3 = 3, ULTI = 4;

    /** Client → serveur : une touche de Kaguya (le clic droit, s'accroupir, la technique, l'ulti). */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("kaguya_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Ce que montre un {@link Effet}. */
    public static final int E_BALLE = 0, E_BALLE_FIN = 1, E_PIQUES = 2, E_FOUET = 3, E_SAULE = 4, E_LANCES = 5, E_FONCE = 6, E_GRAND_FOUET = 7,
            E_FORET = 8, E_PIEU = 9, E_QUEUE = 10, E_EPINES = 11, E_PHALANGES = 12;

    /** Serveur → ceux qui sont autour : un coup d'os. {@code pos}, {@code dir} : où et vers où ; {@code n} : un nombre (l'entité visée…). */
    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("kaguya_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VAR_INT, Effet::phase,
                ByteBufCodecs.VECTOR3F, Effet::pos, ByteBufCodecs.VECTOR3F, Effet::dir, ByteBufCodecs.VAR_INT, Effet::n, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKaguya.effet(p)));
    }

    static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3 autour) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, v(pos), v(dir), n), pos);
    }

    static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    static final BlockParticleOption OS_PART = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());

    /** Un coup du pouvoir : il compte même juste après un autre (les balles, les pieux arrivent ensemble). */
    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats) {
        blesser(joueur, cible, degats, joueur.damageSources().playerAttack(joueur));
    }

    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats, DamageSource source) {
        dansPouvoir = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(source, degats);
        } finally {
            dansPouvoir = false;
        }
    }

    static boolean touchable(ServerPlayer joueur, LivingEntity e) {
        return Pouvoirs.cible(joueur, e) && !e.isSpectator() && !KaguyaDino.avale(e);
    }

    static Vec3 milieu(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    /** La main droite (à peu près), d'où partent les balles et le fouet. */
    static Vec3 main(ServerPlayer joueur) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return joueur.getEyePosition().add(droite.scale(0.35)).add(joueur.getLookAngle().scale(0.4)).add(0, -0.3, 0);
    }

    /** Ce qu'elle vise : le vivant le plus près de son regard, à moins de {@code portee} blocs (dans un cône étroit), ou null. */
    static LivingEntity visee(ServerPlayer joueur, double portee, double cosinus) {
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        LivingEntity meilleure = null;
        double score = Double.MAX_VALUE;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee), e -> touchable(joueur, e))) {
            Vec3 vers = milieu(e).subtract(oeil);
            double d = vers.length();
            if (d > portee || d < 0.01) {
                continue;
            }
            double c = vers.normalize().dot(regard);
            if (c < cosinus || !joueur.hasLineOfSight(e)) {
                continue;
            }
            double s = d * (2 - c);
            if (s < score) {
                score = s;
                meilleure = e;
            }
        }
        return meilleure;
    }

    /** Le sol sous ce point (en descendant jusqu'à 8 blocs ; en montant de 3 s'il est dans le sol). */
    static double sol(ServerLevel monde, Vec3 p) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, p.y + 3, p.z).mutable();
        for (int i = 0; i < 12; i++) {
            if (!monde.getBlockState(pos).getCollisionShape(monde, pos).isEmpty()) {
                return pos.getY() + 1;
            }
            pos.move(0, -1, 0);
        }
        return p.y;
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        int niveau = niveau(joueur);
        if (niveau == 0 || KaguyaDino.actif(joueur)) {
            return;
        }
        if (action == ULTI) {
            if (niveau >= 3) {
                KaguyaDino.lancer(joueur);
            }
            return;
        }
        boolean lances = LANCES.containsKey(joueur.getUUID());
        switch (action) {
            case DROIT -> {
                if (lances) {
                    foncer(joueur);
                } else if (niveau >= 3) {
                    grandFouet(joueur);
                } else if (niveau == 2) {
                    fouet(joueur);
                } else {
                    phalanges(joueur);
                }
            }
            case ACCROUPI -> {
                if (niveau >= 3) {
                    foret(joueur);
                } else if (niveau == 2) {
                    saule(joueur);
                } else {
                    piques(joueur);
                }
            }
            case TECHNIQUE -> {
                if (niveau >= 3) {
                    pieux(joueur);
                } else if (niveau == 2) {
                    lances(joueur, false);
                }
            }
            case LANCES3 -> {
                if (niveau >= 3) {
                    lances(joueur, true);
                } else if (niveau == 2) {
                    lances(joueur, false);
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- niveau 1 : les phalanges (ta-ta-ta-ta-ta)

    private record Balle(ServerPlayer tireur, ServerLevel monde, int id, int depart, Vec3[] pos, Vec3 dir, double[] parcouru) {
    }

    private static final List<Balle> BALLES = new ArrayList<>();
    private static int prochaineBalle;
    /** Les balles qui restent à tirer : le tireur → combien, et quand la suivante part. */
    private static final Map<UUID, int[]> RAFALES = new HashMap<>();

    private static void phalanges(ServerPlayer joueur) {
        if (!pret(PRET_DROIT, joueur)) {
            return;
        }
        armer(PRET_DROIT, joueur, RECHARGE_PHALANGES);
        RAFALES.put(joueur.getUUID(), new int[]{5, joueur.server.getTickCount()});
        effet(joueur, E_PHALANGES, 0, joueur.position(), Vec3.ZERO, 5);
    }

    private static void tirerBalle(ServerPlayer joueur) {
        Vec3 dep = main(joueur);
        Vec3 dir = joueur.getLookAngle();
        int id = ++prochaineBalle;
        BALLES.add(new Balle(joueur, joueur.serverLevel(), id, joueur.server.getTickCount(), new Vec3[]{dep}, dir, new double[]{0}));
        effet(joueur, E_BALLE, 0, dep, dir, id);
        son(joueur.serverLevel(), dep, SoundEvents.BONE_BLOCK_HIT, 1F, 1.6F + joueur.getRandom().nextFloat() * 0.3F);
    }

    private static void balles() {
        BALLES.removeIf(b -> {
            if (b.tireur().isRemoved()) {
                return true;
            }
            Vec3 de = b.pos()[0];
            Vec3 a = de.add(b.dir().scale(VITESSE_BALLE));
            HitResult bloc = b.monde().clip(new ClipContext(de, a, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.tireur()));
            if (bloc.getType() != HitResult.Type.MISS) {
                a = bloc.getLocation();
            }
            LivingEntity touche = null;
            double meilleur = Double.MAX_VALUE;
            for (LivingEntity e : b.monde().getEntitiesOfClass(LivingEntity.class, new AABB(de, a).inflate(0.6), e -> touchable(b.tireur(), e))) {
                var hit = e.getBoundingBox().inflate(0.3).clip(de, a);
                if (hit.isPresent() && hit.get().distanceToSqr(de) < meilleur) {
                    meilleur = hit.get().distanceToSqr(de);
                    touche = e;
                }
            }
            b.parcouru()[0] += de.distanceTo(a);
            b.pos()[0] = a;
            if (touche != null) {
                blesser(b.tireur(), touche, BALLE);
                b.monde().sendParticles(OS_PART, touche.getX(), touche.getY(0.6), touche.getZ(), 8, 0.2, 0.2, 0.2, 0.1);
                envoyer(b.monde(), new Effet(b.tireur().getUUID(), E_BALLE_FIN, 0, v(milieu(touche)), v(b.dir()), b.id()), a);
                return true;
            }
            if (bloc.getType() != HitResult.Type.MISS || b.parcouru()[0] >= PORTEE_BALLES) {
                b.monde().sendParticles(OS_PART, a.x, a.y, a.z, 4, 0.1, 0.1, 0.1, 0.05);
                envoyer(b.monde(), new Effet(b.tireur().getUUID(), E_BALLE_FIN, 0, v(a), v(b.dir()), b.id()), a);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- niveau 1 : les piques du sol

    private record Ligne(ServerPlayer joueur, ServerLevel monde, Vec3 depart, Vec3 dir, int debut, Set<Integer> touches) {
    }

    private static final List<Ligne> LIGNES = new ArrayList<>();

    private static void piques(ServerPlayer joueur) {
        if (!pret(PRET_ACCROUPI, joueur)) {
            return;
        }
        armer(PRET_ACCROUPI, joueur, RECHARGE_PIQUES);
        LivingEntity cible = visee(joueur, PORTEE_PIQUES + 2, 0.8);
        Vec3 dir = cible != null ? cible.position().subtract(joueur.position()) : joueur.getLookAngle();
        dir = new Vec3(dir.x, 0, dir.z);
        if (dir.lengthSqr() < 1e-4) {
            dir = Pouvoirs.devant(joueur);
        }
        dir = dir.normalize();
        Vec3 dep = joueur.position().add(dir.scale(1.2));
        LIGNES.add(new Ligne(joueur, joueur.serverLevel(), dep, dir, joueur.server.getTickCount(), new HashSet<>()));
        effet(joueur, E_PIQUES, 0, dep, dir, (int) PORTEE_PIQUES);
        son(joueur.serverLevel(), dep, SoundEvents.BONE_BLOCK_BREAK, 1.4F, 0.6F);
    }

    private static void lignes(int maintenant) {
        LIGNES.removeIf(l -> {
            int age = maintenant - l.debut();
            // deux piques par tick, une par bloc
            for (int k = Math.max(0, age * 2 - 1); k <= age * 2 && k <= PORTEE_PIQUES; k++) {
                Vec3 p = l.depart().add(l.dir().scale(k));
                double y = sol(l.monde(), p);
                p = new Vec3(p.x, y, p.z);
                for (LivingEntity e : l.monde().getEntitiesOfClass(LivingEntity.class, new AABB(p.x - 1.3, y - 1, p.z - 1.3, p.x + 1.3, y + 3, p.z + 1.3),
                        e -> touchable(l.joueur(), e))) {
                    if (l.touches().add(e.getId())) {
                        blesser(l.joueur(), e, PIQUES);
                        e.setDeltaMovement(e.getDeltaMovement().add(l.dir().scale(0.3)).add(0, 0.95, 0));
                        e.hurtMarked = true;
                    }
                }
                if (k % 2 == 0) {
                    son(l.monde(), p, SoundEvents.BONE_BLOCK_PLACE, 0.8F, 0.5F + k * 0.05F);
                }
            }
            return age * 2 >= PORTEE_PIQUES;
        });
    }

    // ---------------------------------------------------------------- niveau 2 : le fouet, la danse du saule

    private static void fouet(ServerPlayer joueur) {
        if (!pret(PRET_DROIT, joueur)) {
            return;
        }
        armer(PRET_DROIT, joueur, RECHARGE_FOUET);
        Vec3 main = main(joueur);
        LivingEntity cible = visee(joueur, PORTEE_FOUET, 0.94);
        son(joueur.serverLevel(), main, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.6F);
        if (cible == null) {
            effet(joueur, E_FOUET, 0, main, main.add(joueur.getLookAngle().scale(PORTEE_FOUET)), -1);
            return;
        }
        effet(joueur, E_FOUET, 0, main, milieu(cible), cible.getId());
        blesser(joueur, cible, FOUET);
        // il s'enroule autour de la cible et la ramène à elle
        Vec3 vers = joueur.position().subtract(cible.position());
        double d = vers.length();
        cible.setDeltaMovement(vers.normalize().scale(Math.min(2.2, 0.35 + d * 0.2)).add(0, 0.45, 0));
        cible.hurtMarked = true;
        cible.fallDistance = 0;
        son(joueur.serverLevel(), cible.position(), SoundEvents.BONE_BLOCK_HIT, 1.2F, 0.7F);
    }

    private static void saule(ServerPlayer joueur) {
        if (!pret(PRET_ACCROUPI, joueur)) {
            return;
        }
        armer(PRET_ACCROUPI, joueur, RECHARGE_SAULE);
        effet(joueur, E_SAULE, 0, joueur.position(), Vec3.ZERO, 0);
        ServerLevel monde = joueur.serverLevel();
        son(monde, joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 1.6F, 0.5F);
        son(monde, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.4F, 0.7F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON_SAULE, 2, RAYON_SAULE), e -> touchable(joueur, e))) {
            Vec3 loin = e.position().subtract(joueur.position());
            if (loin.horizontalDistance() > RAYON_SAULE) {
                continue;
            }
            blesser(joueur, e, SAULE);
            e.knockback(1.4, -loin.x, -loin.z);
            monde.sendParticles(OS_PART, e.getX(), e.getY(0.6), e.getZ(), 10, 0.3, 0.4, 0.3, 0.1);
        }
        monde.sendParticles(ParticleTypes.SWEEP_ATTACK, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 6, 1.5, 0.3, 1.5, 0);
    }

    // ---------------------------------------------------------------- niveaux 2 et 3 : les lances

    private static void lances(ServerPlayer joueur, boolean grosses) {
        if (LANCES.containsKey(joueur.getUUID()) || !pret(PRET_LANCES, joueur)) {
            return;
        }
        armer(PRET_LANCES, joueur, RECHARGE_LANCES);
        LANCES.put(joueur.getUUID(), new int[]{joueur.server.getTickCount() + DUREE_LANCES, grosses ? 1 : 0});
        retirerEpee(joueur, false);
        // à mains nues : on frappe au rythme d'une épée
        AttributeInstance a = joueur.getAttribute(Attributes.ATTACK_SPEED);
        if (a != null) {
            a.removeModifier(VITESSE_LANCES);
            a.addTransientModifier(new AttributeModifier(VITESSE_LANCES, -2.4, AttributeModifier.Operation.ADD_VALUE));
        }
        effet(joueur, E_LANCES, 0, joueur.position(), Vec3.ZERO, grosses ? 1 : 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 1.6F, 0.4F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.SKELETON_HURT, 1F, 0.5F);
    }

    private static void finirLances(ServerPlayer joueur, boolean prevenir) {
        if (LANCES.remove(joueur.getUUID()) == null) {
            return;
        }
        AttributeInstance a = joueur.getAttribute(Attributes.ATTACK_SPEED);
        if (a != null) {
            a.removeModifier(VITESSE_LANCES);
        }
        CHARGES.remove(joueur.getUUID());
        if (prevenir) {
            effet(joueur, E_LANCES, 1, joueur.position(), Vec3.ZERO, 0);
            son(joueur.serverLevel(), joueur.position(), SoundEvents.BONE_BLOCK_PLACE, 1.2F, 0.7F);
        }
    }

    private record Charge(int fin, Vec3 dir, Set<Integer> touches, boolean grosses) {
    }

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();

    /** Lances en avant, elle fonce 8 blocs : tout ce qu'elle traverse est embroché. */
    private static void foncer(ServerPlayer joueur) {
        if (!pret(PRET_FONCE, joueur)) {
            return;
        }
        armer(PRET_FONCE, joueur, RECHARGE_FONCE);
        Vec3 dir = Pouvoirs.devant(joueur);
        CHARGES.put(joueur.getUUID(), new Charge(joueur.server.getTickCount() + 8, dir, new HashSet<>(), LANCES.get(joueur.getUUID())[1] == 1));
        joueur.setDeltaMovement(dir.scale(2.0).add(0, 0.12, 0));
        joueur.hurtMarked = true;
        effet(joueur, E_FONCE, 0, joueur.position(), dir, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.4F, 0.6F);
    }

    // ---------------------------------------------------------------- niveau 3 : le grand fouet, la forêt, les pieux

    private static void grandFouet(ServerPlayer joueur) {
        if (!pret(PRET_DROIT, joueur)) {
            return;
        }
        armer(PRET_DROIT, joueur, RECHARGE_GRAND_FOUET);
        effet(joueur, E_GRAND_FOUET, 0, joueur.position(), Vec3.directionFromRotation(0, joueur.getYRot()), (int) RAYON_GRAND_FOUET);
        ServerLevel monde = joueur.serverLevel();
        son(monde, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 2F, 0.4F);
        son(monde, joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 1.6F, 0.5F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON_GRAND_FOUET, 3, RAYON_GRAND_FOUET), e -> touchable(joueur, e))) {
            Vec3 loin = e.position().subtract(joueur.position());
            if (loin.horizontalDistance() > RAYON_GRAND_FOUET) {
                continue;
            }
            blesser(joueur, e, GRAND_FOUET);
            e.knockback(1.2, -loin.x, -loin.z);
            // par terre : il a du mal à se relever
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2));
            monde.sendParticles(OS_PART, e.getX(), e.getY(0.4), e.getZ(), 10, 0.3, 0.3, 0.3, 0.1);
        }
    }

    /** Une forêt d'os : le centre, la graine (le client pousse les mêmes piques), quand elle s'effrite. */
    private record Foret(ServerPlayer joueur, ServerLevel monde, Vec3 centre, int graine, int fin) {
    }

    private static final List<Foret> FORETS = new ArrayList<>();

    /** Les piques de la forêt (les mêmes chez le client) : x, z autour du centre, le rayon, la hauteur (en blocs). */
    public static float[][] foret(int graine) {
        float[][] l = new float[72][];
        for (int k = 0; k < l.length; k++) {
            double r = 2.6 + (RAYON_FORET - 2.6) * Math.sqrt(hache(k, 1, graine)), a = hache(k, 2, graine) * Mth.TWO_PI;
            double rayon = 0.7 + 0.8 * hache(k, 3, graine), haut = 3.5 + 6 * hache(k, 4, graine) * (1 - r / (RAYON_FORET * 1.6));
            l[k] = new float[]{(float) (Math.cos(a) * r), (float) (Math.sin(a) * r), (float) rayon, (float) haut};
        }
        return l;
    }

    public static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static void foret(ServerPlayer joueur) {
        if (!pret(PRET_ACCROUPI, joueur)) {
            return;
        }
        armer(PRET_ACCROUPI, joueur, RECHARGE_FORET);
        int graine = joueur.getRandom().nextInt(100_000);
        ServerLevel monde = joueur.serverLevel();
        Vec3 c = joueur.position();
        FORETS.add(new Foret(joueur, monde, c, graine, joueur.server.getTickCount() + DUREE_FORET));
        effet(joueur, E_FORET, 0, c, Vec3.ZERO, graine);
        son(monde, c, SoundEvents.BONE_BLOCK_BREAK, 2F, 0.4F);
        son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.4F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON_FORET, 4, RAYON_FORET), e -> touchable(joueur, e))) {
            if (e.position().subtract(c).horizontalDistance() > RAYON_FORET) {
                continue;
            }
            blesser(joueur, e, FORET);
            e.setDeltaMovement(e.getDeltaMovement().add(0, 1.0, 0));
            e.hurtMarked = true;
            monde.sendParticles(OS_PART, e.getX(), e.getY(), e.getZ(), 14, 0.4, 0.2, 0.4, 0.15);
        }
    }

    /** Les piques restent 6 s : on ne passe pas au travers (ceux qui y entrent sont repoussés). */
    private static void forets(int maintenant) {
        FORETS.removeIf(f -> {
            if (maintenant >= f.fin()) {
                effet(f.joueur(), E_FORET, 1, f.centre(), Vec3.ZERO, f.graine());
                son(f.monde(), f.centre(), SoundEvents.BONE_BLOCK_BREAK, 1.6F, 0.8F);
                return true;
            }
            float[][] pics = foret(f.graine());
            for (LivingEntity e : f.monde().getEntitiesOfClass(LivingEntity.class, new AABB(f.centre(), f.centre()).inflate(RAYON_FORET + 2, 6, RAYON_FORET + 2),
                    e -> e != f.joueur() && !e.isSpectator() && !KaguyaDino.avale(e))) {
                for (float[] p : pics) {
                    double dx = e.getX() - (f.centre().x + p[0]), dz = e.getZ() - (f.centre().z + p[1]);
                    double d = Math.sqrt(dx * dx + dz * dz), min = p[2] + e.getBbWidth() / 2 + 0.1;
                    if (d < min && e.getY() < f.centre().y + p[3]) {
                        double k = d < 1e-3 ? 1 : (min - d) / d;
                        e.setDeltaMovement(e.getDeltaMovement().add(dx * k * 0.6, 0, dz * k * 0.6));
                        e.hurtMarked = true;
                    }
                }
            }
            return false;
        });
    }

    private record Pieu(ServerPlayer joueur, ServerLevel monde, int cible, Vec3 point, int impact) {
    }

    private static final List<Pieu> PIEUX = new ArrayList<>();

    /** Les 6 os pointus de son dos s'arrachent, montent, puis plongent sur ce qui est devant elle (jusqu'à 6 cibles). */
    private static void pieux(ServerPlayer joueur) {
        if (!pret(PRET_TECH, joueur)) {
            return;
        }
        armer(PRET_TECH, joueur, RECHARGE_PIEUX);
        ServerLevel monde = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle();
        List<LivingEntity> cibles = new ArrayList<>(monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_PIEUX), e -> {
            Vec3 vers = e.position().subtract(joueur.position());
            return touchable(joueur, e) && vers.length() <= PORTEE_PIEUX && (vers.lengthSqr() < 4 || vers.normalize().dot(regard) > 0.4);
        }));
        cibles.sort(Comparator.comparingDouble(e -> e.distanceToSqr(joueur)));
        int maintenant = joueur.server.getTickCount();
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet)), dos = new Vec3(Mth.sin(lacet), 0, -Mth.cos(lacet));
        for (int i = 0; i < 6; i++) {
            LivingEntity c = cibles.isEmpty() ? null : cibles.get(i % Math.min(6, cibles.size()));
            Vec3 point = c != null ? milieu(c) : joueur.position().add(Pouvoirs.devant(joueur).scale(8 + i * 2.5)).add(droite.scale((i % 2 * 2 - 1) * (1 + i / 2)));
            if (c == null) {
                point = new Vec3(point.x, sol(monde, point), point.z);
            }
            int impact = maintenant + PIEU_MONTE + i * PIEU_ECART + PIEU_PLONGE;
            PIEUX.add(new Pieu(joueur, monde, c == null ? -1 : c.getId(), point, impact));
            // où il est quand il s'arrache (le haut du dos, à gauche puis à droite)
            Vec3 dep = joueur.position().add(0, 1.2 + (i / 2) * 0.15, 0).add(dos.scale(0.3)).add(droite.scale(i % 2 == 0 ? -0.3 : 0.3));
            effet(joueur, E_PIEU, i, dep, point, c == null ? -1 : c.getId());
        }
        son(monde, joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 1.8F, 0.5F);
        son(monde, joueur.position(), SoundEvents.SKELETON_HURT, 1F, 0.4F);
    }

    private static void pieux(int maintenant) {
        PIEUX.removeIf(p -> {
            if (maintenant < p.impact()) {
                return false;
            }
            Vec3 point = p.point();
            if (p.cible() >= 0 && p.monde().getEntity(p.cible()) instanceof LivingEntity c && c.isAlive()) {
                point = milieu(c);
            }
            for (LivingEntity e : p.monde().getEntitiesOfClass(LivingEntity.class, new AABB(point, point).inflate(1.6), e -> touchable(p.joueur(), e))) {
                blesser(p.joueur(), e, PIEU);
            }
            p.monde().sendParticles(OS_PART, point.x, point.y, point.z, 16, 0.3, 0.3, 0.3, 0.15);
            son(p.monde(), point, SoundEvents.BONE_BLOCK_BREAK, 1.2F, 0.8F);
            return true;
        });
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(EVEILLES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null) {
                EVEILLES.remove(en.getKey());
                continue;
            }
            int niveau = en.getValue();
            if (maintenant % 20 == 0) {
                resistance(joueur, niveau);
            }
            // les lances : 30 s, puis l'épée revient
            int[] l = LANCES.get(joueur.getUUID());
            if (l != null && maintenant >= l[0]) {
                finirLances(joueur, true);
            }
            if (niveau >= 2 && !LANCES.containsKey(joueur.getUUID()) && maintenant - DEBUT_2.getOrDefault(joueur.getUUID(), -1000) >= EPEE_PRETE) {
                bloquerEpee(joueur);
            }
            // la queue (niveau 3) frappe toute seule ceux qui sont derrière elle
            if (niveau >= 3 && maintenant % 40 == 0 && !KaguyaDino.actif(joueur)) {
                queue(joueur);
            }
            // les phalanges : une balle tous les 2 ticks
            int[] r = RAFALES.get(joueur.getUUID());
            if (r != null && maintenant >= r[1]) {
                tirerBalle(joueur);
                r[0]--;
                r[1] = maintenant + 2;
                if (r[0] <= 0) {
                    RAFALES.remove(joueur.getUUID());
                }
            }
        }
        RAFALES.keySet().removeIf(u -> !EVEILLES.containsKey(u));
        // elle fonce, lances en avant
        for (Map.Entry<UUID, Charge> c : new ArrayList<>(CHARGES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(c.getKey());
            Charge ch = c.getValue();
            if (joueur == null || maintenant > ch.fin()) {
                CHARGES.remove(c.getKey());
                continue;
            }
            joueur.fallDistance = 0;
            for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(1.3).expandTowards(ch.dir().scale(1.6)),
                    e -> touchable(joueur, e))) {
                if (ch.touches().add(e.getId())) {
                    blesser(joueur, e, ch.grosses() ? FONCE * 1.75F / 1.5F : FONCE);
                    e.knockback(1.5, -ch.dir().x, -ch.dir().z);
                    joueur.serverLevel().sendParticles(OS_PART, e.getX(), e.getY(0.5), e.getZ(), 12, 0.3, 0.4, 0.3, 0.1);
                }
            }
        }
        balles();
        lignes(maintenant);
        forets(maintenant);
        pieux(maintenant);
    }

    private static void queue(ServerPlayer joueur) {
        Vec3 devant = Pouvoirs.devant(joueur);
        boolean frappe = false;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(4, 1.5, 4), e -> touchable(joueur, e))) {
            Vec3 vers = e.position().subtract(joueur.position());
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            if (plat.length() > 4 || plat.lengthSqr() < 0.01 || plat.normalize().dot(devant) > -0.3) {
                continue;
            }
            blesser(joueur, e, QUEUE);
            e.knockback(0.8, -plat.x, -plat.z);
            frappe = true;
        }
        if (frappe) {
            effet(joueur, E_QUEUE, 0, joueur.position(), devant.scale(-1), 0);
            son(joueur.serverLevel(), joueur.position(), SoundEvents.PLAYER_ATTACK_STRONG, 1F, 0.6F);
        }
    }

    // ================================================================ les coups qu'elle donne et ceux qu'elle prend

    /**
     * Ses coups au corps à corps : poings d'os (7) à mains nues, l'épée colonne (11, puis 14 au niveau 3), les lances
     * (qui traversent l'armure). Et quand on la frappe : une plaque d'os pousse sous sa peau, et celui qui l'a frappée au
     * corps à corps se pique sur ses os (comme l'enchantement Épines).
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource s = event.getSource();
        if (!dansPouvoir && s.is(DamageTypes.PLAYER_ATTACK) && s.getEntity() instanceof ServerPlayer j && s.getDirectEntity() == j) {
            int niveau = niveau(j);
            if (niveau > 0) {
                double base = Math.max(1, j.getAttributeValue(Attributes.ATTACK_DAMAGE));
                double k = event.getAmount() / base;
                int[] lances = LANCES.get(j.getUUID());
                if (lances != null && j.getMainHandItem().isEmpty()) {
                    event.setCanceled(true);
                    if (event.getEntity() instanceof LivingEntity cible) {
                        blesser(j, cible, (float) ((lances[1] == 1 ? LANCES_3 : LANCES_2) * k), j.damageSources().indirectMagic(j, j));
                        j.serverLevel().sendParticles(OS_PART, cible.getX(), cible.getY(0.6), cible.getZ(), 8, 0.2, 0.3, 0.2, 0.1);
                    }
                    return;
                }
                if (tientEpee(j)) {
                    event.setAmount((float) ((niveau >= 3 ? EPEE_3 : EPEE_2) * k));
                } else if (j.getMainHandItem().isEmpty()) {
                    event.setAmount((float) (POINGS * k));
                }
            }
        }
        if (event.getEntity() instanceof ServerPlayer k && niveau(k) > 0 && !s.is(DamageTypes.THORNS)) {
            ServerLevel monde = k.serverLevel();
            monde.sendParticles(OS_PART, k.getX(), k.getY(0.6), k.getZ(), 6, 0.25, 0.35, 0.25, 0.05);
            if (s.getDirectEntity() instanceof LivingEntity attaquant && s.getEntity() == attaquant && attaquant != k && attaquant.isAlive()
                    && attaquant.distanceTo(k) < 6) {
                float epines = 1 + k.getRandom().nextInt(niveau(k) + 1);
                attaquant.hurt(k.damageSources().thorns(k), epines);
                monde.sendParticles(OS_PART, attaquant.getX(), attaquant.getY(0.6), attaquant.getZ(), 10, 0.25, 0.35, 0.25, 0.08);
                monde.sendParticles(ParticleTypes.CRIT, attaquant.getX(), attaquant.getY(0.6), attaquant.getZ(), 6, 0.25, 0.35, 0.25, 0.1);
                son(monde, attaquant.position(), SoundEvents.THORNS_HIT, 1F, 0.9F + k.getRandom().nextFloat() * 0.2F);
                effet(k, E_EPINES, 0, milieu(attaquant), milieu(k), attaquant.getId());
            }
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            ItemStack avant = RANGES.remove(joueur.getUUID());
            if (avant != null && !avant.isEmpty() && !joueur.getInventory().add(avant)) {
                joueur.drop(avant, false);
            }
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        DEBUT_2.clear();
        PRET_DROIT.clear();
        PRET_ACCROUPI.clear();
        PRET_TECH.clear();
        PRET_LANCES.clear();
        PRET_FONCE.clear();
        LANCES.clear();
        CHARGES.clear();
        BALLES.clear();
        RAFALES.clear();
        LIGNES.clear();
        FORETS.clear();
        PIEUX.clear();
        RANGES.clear();
    }

    private Kaguya() {
    }
}
