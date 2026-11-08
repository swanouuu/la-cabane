package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientEspionne;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.joml.Vector3f;

/**
 * Les gadgets des espionnes du WOOHP, d'après les maquettes de docs/spies. Ce n'est plus un pouvoir : ce sont des objets,
 * que le bouton des gadgets du QG fait sortir sur le tapis roulant (voir labo/WoohpGadgets).
 * <ul>
 * <li>en main, au clic droit : le rouge à lèvres laser (un rayon fin, 4 cœurs), le sèche-cheveux (souffle tout ce qui est
 * devant), le parfum (ralentit et aveugle 3 s), le chewing-gum (colle la cible visée dans une bulle 2 s) ;</li>
 * <li>dans les accessoires (Curios) : les lunettes infrarouges sur la tête (on voit les créatures à travers les murs),
 * les bottes à talons aux pieds (double saut, plus aucun dégât de chute), le grappin à la ceinture (un appui court sur
 * accroupi tire vers le mur visé) ;</li>
 * <li>la tenue, verte (Sam), rouge (Clover) ou jaune (Alex) : un clic droit l'enfile (zip !), à la place du plastron, et
 * on l'enlève dans l'inventaire quand on veut. Elle protège comme un plastron de fer.</li>
 * </ul>
 * Chaque gadget attend 1 à 2 secondes entre deux coups. Rien ne vise les autres pouvoirs : un Susano prend tout ça comme
 * n'importe quel joueur.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Espionne {
    /** Les gadgets tenus en main, dans l'ordre de RECHARGES. */
    public static final int LASER = 0;
    public static final int SECHE_CHEVEUX = 1;
    public static final int PARFUM = 2;
    public static final int CHEWING_GUM = 3;
    public static final String[] GADGETS = {"rouge_a_levres", "seche_cheveux", "parfum", "chewing_gum"};
    /** Entre deux coups (en ticks), pour chaque gadget en main. */
    public static final int[] RECHARGES = {20, 30, 40, 40};
    /** Les accessoires (Curios) : les lunettes sur la tête, les bottes aux pieds, le grappin à la ceinture. */
    public static final int LUNETTES = 0;
    public static final int BOTTES = 1;
    public static final int GRAPPIN = 2;
    public static final String[] ACCESSOIRES = {"lunettes", "bottes", "grappin"};
    public static final int RECHARGE_GRAPPIN = 20;
    /** Les tenues : celles de Sam, Clover et Alex. */
    public static final String[] TENUES = {"verte", "rouge", "jaune"};

    private static final double PORTEE_LASER = 24;
    /** 4 cœurs par tir, à 24 blocs. */
    private static final float DEGATS_LASER = 8;
    private static final double PORTEE_SOUFFLE = 7;
    private static final double PORTEE_PARFUM = 5;
    private static final int DUREE_PARFUM = 60;
    private static final double PORTEE_GUM = 16;
    public static final int DUREE_BULLE = 40;
    private static final double PORTEE_GRAPPIN = 28;
    /** Le grappin tire au plus 1,5 s, à 1,3 bloc par tick. */
    private static final int DUREE_GRAPPIN = 30;
    private static final double VITESSE_GRAPPIN = 1.3;

    /** Quand le grappin de chacune est de nouveau prêt (tick du serveur). */
    private static final Map<UUID, Integer> GRAPPIN_PRET = new HashMap<>();
    /** Celles qui ont déjà fait leur double saut depuis qu'elles ont quitté le sol. */
    private static final Set<UUID> SAUTEES = new HashSet<>();

    /** Une cible collée dans une bulle de chewing-gum : où elle reste, et jusqu'à quand. */
    private record Bulle(ServerLevel monde, Vec3 pos, int fin) {
    }

    private static final Map<Integer, Bulle> BULLES = new HashMap<>();

    /** Un grappin accroché : le point dans le mur, et jusqu'à quand il tire. */
    private record Accroche(Vec3 point, int fin) {
    }

    private static final Map<UUID, Accroche> GRAPPINS = new HashMap<>();

    // ================================================================ les objets

    /** Le bruit de la fermeture éclair, quand on enfile une tenue (synthétisé par outils/espionnes.mjs). */
    public static final ResourceLocation ZIP = Cabane.id("espionne.zip");

    public static ResourceLocation gadget(int i) {
        return Cabane.id("espionne_" + GADGETS[i]);
    }

    public static ResourceLocation accessoire(int i) {
        return Cabane.id("espionne_" + ACCESSOIRES[i]);
    }

    public static ResourceLocation tenue(int i) {
        return Cabane.id("espionne_tenue_" + TENUES[i]);
    }

    /** Tout ce que donne le bouton des gadgets : les quatre gadgets, les trois accessoires et les trois tenues. */
    public static List<ItemStack> panoplie() {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < GADGETS.length; i++) {
            l.add(new ItemStack(BuiltInRegistries.ITEM.get(gadget(i))));
        }
        for (int i = 0; i < ACCESSOIRES.length; i++) {
            l.add(new ItemStack(BuiltInRegistries.ITEM.get(accessoire(i))));
        }
        for (int i = 0; i < TENUES.length; i++) {
            l.add(new ItemStack(BuiltInRegistries.ITEM.get(tenue(i))));
        }
        return l;
    }

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        event.register(Registries.SOUND_EVENT, ZIP, () -> SoundEvent.createVariableRangeEvent(ZIP));
        for (int i = 0; i < GADGETS.length; i++) {
            int g = i;
            event.register(Registries.ITEM, gadget(i), () -> new GadgetEnMain(g));
        }
        for (int i = 0; i < ACCESSOIRES.length; i++) {
            event.register(Registries.ITEM, accessoire(i), Accessoire::new);
        }
        for (int i = 0; i < TENUES.length; i++) {
            int c = i;
            event.register(Registries.ITEM, tenue(i), () -> new Tenue(c));
        }
    }

    @SubscribeEvent
    public static void onglet(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == fr.cabane.CabaneItems.ONGLET.get()) {
            panoplie().forEach(event::accept);
        }
    }

    /** Les accessoires se portent dans les slots de Curios (il est dans le pack ; sans lui, ils ne se portent pas). */
    private static final boolean CURIOS = ModList.get().isLoaded("curios");

    @SubscribeEvent
    public static void installer(FMLCommonSetupEvent event) {
        if (CURIOS) {
            event.enqueueWork(() -> {
                for (int i = 0; i < ACCESSOIRES.length; i++) {
                    EspionneCurios.portable(BuiltInRegistries.ITEM.get(accessoire(i)));
                }
            });
        }
    }

    /** Porte-t-il cet accessoire (LUNETTES, BOTTES, GRAPPIN) dans un slot de Curios ? Marche aussi chez les clients. */
    public static boolean porte(LivingEntity e, int accessoire) {
        return CURIOS && EspionneCurios.porte(e, BuiltInRegistries.ITEM.get(accessoire(accessoire)));
    }

    /** La tenue qu'il a sur lui (0 verte, 1 rouge, 2 jaune), ou -1. */
    public static int tenue(Player joueur) {
        return joueur.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof Tenue t ? t.couleur : -1;
    }

    /** Un gadget tenu en main : un clic droit s'en sert (et le voilà au repos 1 à 2 s). */
    public static final class GadgetEnMain extends Item {
        final int gadget;

        GadgetEnMain(int gadget) {
            super(new Item.Properties().stacksTo(1));
            this.gadget = gadget;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            ItemStack objet = joueur.getItemInHand(main);
            if (!level.isClientSide && joueur instanceof ServerPlayer j && !j.isSpectator()) {
                switch (gadget) {
                    case LASER -> laser(j);
                    case SECHE_CHEVEUX -> souffler(j);
                    case PARFUM -> parfum(j);
                    default -> chewingGum(j);
                }
            }
            joueur.getCooldowns().addCooldown(this, RECHARGES[gadget]);
            return InteractionResultHolder.sidedSuccess(objet, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Un accessoire : un clic droit avec lui en main le met dans son slot (Curios). */
    public static final class Accessoire extends Item {
        Accessoire() {
            super(new Item.Properties().stacksTo(1));
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        }
    }

    /** La tenue d'une espionne : un clic droit l'enfile à la place du plastron, avec le bruit de la fermeture éclair. */
    public static final class Tenue extends Item implements Equipable {
        final int couleur;

        Tenue(int couleur) {
            super(new Item.Properties().stacksTo(1).attributes(ItemAttributeModifiers.builder()
                    .add(Attributes.ARMOR, new AttributeModifier(Cabane.id("espionne_tenue"), 6, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
                    .build()));
            this.couleur = couleur;
        }

        @Override
        public EquipmentSlot getEquipmentSlot() {
            return EquipmentSlot.CHEST;
        }

        @Override
        public Holder<SoundEvent> getEquipSound() {
            return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(BuiltInRegistries.SOUND_EVENT.get(ZIP));
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            return swapWithEquipmentSlot(this, level, joueur, main);
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.translatable("item.cabane.espionne_tenue.desc").withStyle(ChatFormatting.GRAY));
        }
    }

    // ================================================================ réseau

    /** Client → serveur : un appui court sur accroupi, sans clic (le grappin). */
    public record Grappin() implements CustomPacketPayload {
        public static final Type<Grappin> TYPE = new Type<>(Cabane.id("espionne_grappin"));
        public static final StreamCodec<ByteBuf, Grappin> CODEC = StreamCodec.unit(new Grappin());

        @Override
        public Type<Grappin> type() {
            return TYPE;
        }
    }

    /** Client → serveur : sauter en l'air (le double saut des talons). */
    public record Saut() implements CustomPacketPayload {
        public static final Type<Saut> TYPE = new Type<>(Cabane.id("espionne_saut"));
        public static final StreamCodec<ByteBuf, Saut> CODEC = StreamCodec.unit(new Saut());

        @Override
        public Type<Saut> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un rayon de rouge à lèvres, de « de » à « a ». */
    public record Rayon(Vector3f de, Vector3f a) implements CustomPacketPayload {
        public static final Type<Rayon> TYPE = new Type<>(Cabane.id("espionne_rayon"));
        public static final StreamCodec<ByteBuf, Rayon> CODEC = StreamCodec.composite(
                ByteBufCodecs.VECTOR3F, Rayon::de, ByteBufCodecs.VECTOR3F, Rayon::a, Rayon::new);

        @Override
        public Type<Rayon> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : cette entité est collée dans une bulle de chewing-gum pendant {@code ticks}. */
    public record BulleEtat(int entite, int ticks) implements CustomPacketPayload {
        public static final Type<BulleEtat> TYPE = new Type<>(Cabane.id("espionne_bulle"));
        public static final StreamCodec<ByteBuf, BulleEtat> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BulleEtat::entite, ByteBufCodecs.VAR_INT, BulleEtat::ticks, BulleEtat::new);

        @Override
        public Type<BulleEtat> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : le câble du grappin de cette espionne, accroché en {@code point} (ou décroché). */
    public record Cable(UUID joueur, boolean accroche, Vector3f point) implements CustomPacketPayload {
        public static final Type<Cable> TYPE = new Type<>(Cabane.id("espionne_cable"));
        public static final StreamCodec<ByteBuf, Cable> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Cable::joueur, ByteBufCodecs.BOOL, Cable::accroche, ByteBufCodecs.VECTOR3F, Cable::point, Cable::new);

        @Override
        public Type<Cable> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Grappin.TYPE, Grappin.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        grappin(joueur);
                    }
                }))
                .playToServer(Saut.TYPE, Saut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        sauter(joueur);
                    }
                }))
                .playToClient(Rayon.TYPE, Rayon.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientEspionne.rayon(p)))
                .playToClient(BulleEtat.TYPE, BulleEtat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientEspionne.bulle(p)))
                .playToClient(Cable.TYPE, Cable.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientEspionne.cable(p)));
    }

    /** À tous ceux qui sont à moins de 96 blocs de ce point. */
    private static void envoyer(ServerLevel monde, Vec3 autour, CustomPacketPayload paquet) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 96 * 96) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    /** D'où part ce qu'elle tient en main droite (le bout du gadget), à peu près. */
    private static Vec3 main(ServerPlayer joueur) {
        Vec3 regard = joueur.getLookAngle();
        Vec3 droite = new Vec3(-regard.z, 0, regard.x).normalize();
        return joueur.getEyePosition().add(regard.scale(0.5)).add(droite.scale(0.32)).add(0, -0.38, 0);
    }

    /** Ce qui vit en premier sur la ligne de son regard (jusqu'au premier mur), et le point où s'arrête cette ligne. */
    private static EntityHitResult viser(ServerPlayer joueur, double portee, Vec3[] fin) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(portee));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        fin[0] = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, fin[0], new AABB(oeil, fin[0]).inflate(1),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !l.isSpectator(), 0F);
        if (touche != null) {
            fin[0] = touche.getLocation();
        }
        return touche;
    }

    private static void son(ServerPlayer joueur, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getEyeY(), joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur);
    }

    // ================================================================ les gadgets en main

    private static final DustParticleOptions ROSE = new DustParticleOptions(new Vector3f(1F, 0.3F, 0.6F), 1.2F);

    private static void laser(ServerPlayer joueur) {
        Vec3[] fin = new Vec3[1];
        EntityHitResult touche = viser(joueur, PORTEE_LASER, fin);
        ServerLevel monde = joueur.serverLevel();
        envoyer(monde, joueur.position(), new Rayon(v(main(joueur)), v(fin[0])));
        son(joueur, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F, 1.9F);
        son(joueur, SoundEvents.FIRECHARGE_USE, 0.35F, 2F);
        monde.sendParticles(ROSE, fin[0].x, fin[0].y, fin[0].z, 10, 0.12, 0.12, 0.12, 0.02);
        if (touche != null && touche.getEntity() instanceof LivingEntity cible) {
            cible.hurt(joueur.damageSources().playerAttack(joueur), DEGATS_LASER);
            monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, fin[0].x, fin[0].y, fin[0].z, 8, 0.15, 0.15, 0.15, 0.2);
        }
    }

    /** Ce qui vit dans le cône devant elle (jusqu'à {@code portee}, plus ouvert quand {@code ouverture} est petit). */
    private static Iterable<LivingEntity> devant(ServerPlayer joueur, double portee, double ouverture) {
        Vec3 oeil = joueur.getEyePosition();
        Vec3 regard = joueur.getLookAngle();
        List<LivingEntity> l = new ArrayList<>();
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 vers = e.position().add(0, e.getBbHeight() / 2, 0).subtract(oeil);
            double d = vers.length();
            if (d <= portee && (d < 0.8 || vers.scale(1 / d).dot(regard) >= ouverture)) {
                l.add(e);
            }
        }
        return l;
    }

    /** Une trainee de particules qui part de sa main dans le sens du regard. */
    private static void trainee(ServerPlayer joueur, net.minecraft.core.particles.ParticleOptions particule, int n, double vitesse, double ecart) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 depart = main(joueur);
        Vec3 regard = joueur.getLookAngle();
        var hasard = joueur.getRandom();
        for (int i = 0; i < n; i++) {
            Vec3 d = regard.add((hasard.nextDouble() - 0.5) * ecart, (hasard.nextDouble() - 0.5) * ecart, (hasard.nextDouble() - 0.5) * ecart).normalize();
            monde.sendParticles(particule, depart.x, depart.y, depart.z, 0, d.x, d.y, d.z, vitesse * (0.6 + hasard.nextDouble() * 0.6));
        }
    }

    /** Le sèche-cheveux : une rafale qui envoie voler tout ce qui est devant (plus fort de près). */
    private static void souffler(ServerPlayer joueur) {
        Vec3 regard = joueur.getLookAngle();
        for (LivingEntity e : devant(joueur, PORTEE_SOUFFLE, 0.72)) {
            double d = e.distanceTo(joueur);
            double force = 2.0 * (1 - d / (PORTEE_SOUFFLE + 2));
            e.setDeltaMovement(e.getDeltaMovement().add(regard.x * force, 0.4 + Math.max(0, regard.y) * force, regard.z * force));
            e.hurtMarked = true;
        }
        trainee(joueur, ParticleTypes.CLOUD, 34, 0.9, 0.45);
        trainee(joueur, ParticleTypes.POOF, 16, 0.7, 0.6);
        son(joueur, SoundEvents.BREEZE_SHOOT, 1.2F, 1.3F);
        son(joueur, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 0.7F, 1.4F);
    }

    private static final DustParticleOptions LAVANDE = new DustParticleOptions(new Vector3f(0.82F, 0.62F, 1F), 1.6F);

    /** Le parfum : un nuage qui ralentit et aveugle 3 secondes ce qu'il touche. */
    private static void parfum(ServerPlayer joueur) {
        for (LivingEntity e : devant(joueur, PORTEE_PARFUM, 0.55)) {
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DUREE_PARFUM, 1), joueur);
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DUREE_PARFUM, 0), joueur);
            joueur.serverLevel().sendParticles(LAVANDE, e.getX(), e.getEyeY(), e.getZ(), 14, 0.35, 0.25, 0.35, 0.01);
        }
        trainee(joueur, LAVANDE, 40, 0.5, 0.5);
        trainee(joueur, ParticleTypes.EFFECT, 12, 0.3, 0.6);
        son(joueur, SoundEvents.FIRE_EXTINGUISH, 0.5F, 1.8F);
        son(joueur, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 1.2F);
    }

    /** Le chewing-gum : la cible visée reste collée dans une bulle rose. */
    private static void chewingGum(ServerPlayer joueur) {
        Vec3[] fin = new Vec3[1];
        EntityHitResult touche = viser(joueur, PORTEE_GUM, fin);
        son(joueur, SoundEvents.SLIME_JUMP_SMALL, 1F, 1.6F);
        if (touche == null || !(touche.getEntity() instanceof LivingEntity cible)) {
            joueur.displayClientMessage(Component.literal("Raté !").withStyle(ChatFormatting.LIGHT_PURPLE), true);
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        BULLES.put(cible.getId(), new Bulle(monde, cible.position(), joueur.server.getTickCount() + DUREE_BULLE));
        envoyer(monde, cible.position(), new BulleEtat(cible.getId(), DUREE_BULLE));
        monde.playSound(null, cible.blockPosition(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.PLAYERS, 1.2F, 1.4F);
    }

    // ================================================================ les bottes et le grappin

    private static void sauter(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (joueur.isSpectator() || !porte(joueur, BOTTES) || joueur.onGround() || joueur.getAbilities().flying || joueur.isInWater()
                || !SAUTEES.add(id)) {
            return;
        }
        Vec3 regard = joueur.getLookAngle();
        Vec3 plat = new Vec3(regard.x, 0, regard.z);
        plat = plat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : plat.normalize().scale(0.32);
        joueur.setDeltaMovement(plat.x, 0.62, plat.z);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(ParticleTypes.FLAME, joueur.getX(), joueur.getY(), joueur.getZ(), 12, 0.12, 0.05, 0.12, 0.03);
        monde.sendParticles(ParticleTypes.SMALL_FLAME, joueur.getX(), joueur.getY(), joueur.getZ(), 10, 0.15, 0.05, 0.15, 0.05);
        monde.sendParticles(ParticleTypes.CLOUD, joueur.getX(), joueur.getY(), joueur.getZ(), 8, 0.25, 0.05, 0.25, 0.02);
        son(joueur, SoundEvents.FIRECHARGE_USE, 0.45F, 1.5F);
    }

    private static void grappin(ServerPlayer joueur) {
        if (joueur.isSpectator() || !porte(joueur, GRAPPIN)) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(PORTEE_GRAPPIN));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        if (bloc.getType() == HitResult.Type.MISS) {
            joueur.displayClientMessage(Component.literal("Rien où accrocher le grappin.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < GRAPPIN_PRET.getOrDefault(joueur.getUUID(), 0)) {
            return;
        }
        GRAPPIN_PRET.put(joueur.getUUID(), maintenant + RECHARGE_GRAPPIN);
        Vec3 point = bloc.getLocation();
        GRAPPINS.put(joueur.getUUID(), new Accroche(point, maintenant + DUREE_GRAPPIN));
        envoyer(monde, joueur.position(), new Cable(joueur.getUUID(), true, v(point)));
        son(joueur, SoundEvents.FISHING_BOBBER_THROW, 1F, 1.3F);
        monde.playSound(null, point.x, point.y, point.z, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1F, 1.2F);
    }

    private static void decrocher(ServerPlayer joueur) {
        if (GRAPPINS.remove(joueur.getUUID()) != null) {
            envoyer(joueur.serverLevel(), joueur.position(), new Cable(joueur.getUUID(), false, new Vector3f()));
        }
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // de retour au sol, le double saut se recharge
        SAUTEES.removeIf(id -> {
            ServerPlayer j = server.getPlayerList().getPlayer(id);
            return j == null || j.onGround() || j.isInWater() || j.onClimbable();
        });
        // le grappin tire vers son point, jusqu'à y être (ou au bout de 1,5 s)
        for (Map.Entry<UUID, Accroche> e : new ArrayList<>(GRAPPINS.entrySet())) {
            ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
            if (j == null) {
                GRAPPINS.remove(e.getKey());
                continue;
            }
            Accroche a = e.getValue();
            Vec3 corps = j.position().add(0, 0.9, 0);
            Vec3 vers = a.point().subtract(corps);
            double d = vers.length();
            if (d < 1.4 || maintenant > a.fin() || !porte(j, GRAPPIN)) {
                decrocher(j);
                // arrivée contre le mur : un petit élan vers le haut, pour se hisser sur un rebord
                if (d < 1.4 && a.point().y > j.getY()) {
                    j.setDeltaMovement(vers.x * 0.1, 0.45, vers.z * 0.1);
                    j.hurtMarked = true;
                }
                continue;
            }
            j.setDeltaMovement(vers.scale(Math.min(VITESSE_GRAPPIN, d * 0.5) / d));
            j.hurtMarked = true;
            j.fallDistance = 0;
        }
        // les bulles de chewing-gum tiennent leur prisonnier sur place, puis éclatent
        for (Map.Entry<Integer, Bulle> e : new ArrayList<>(BULLES.entrySet())) {
            Bulle b = e.getValue();
            Entity cible = b.monde().getEntity(e.getKey());
            if (!(cible instanceof LivingEntity vivant) || !vivant.isAlive() || maintenant >= b.fin()) {
                BULLES.remove(e.getKey());
                if (cible != null) {
                    b.monde().playSound(null, cible.blockPosition(), SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 1.6F, 0.8F);
                    b.monde().sendParticles(ROSE, cible.getX(), cible.getY(0.5), cible.getZ(), 30, 0.6, 0.8, 0.6, 0.05);
                }
                continue;
            }
            if (cible.position().distanceToSqr(b.pos()) > 0.0025) {
                cible.teleportTo(b.pos().x, b.pos().y, b.pos().z);
            }
            cible.setDeltaMovement(Vec3.ZERO);
            cible.hurtMarked = true;
            cible.fallDistance = 0;
        }
    }

    /** Avec les bottes à talons, plus aucun dégât de chute. */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && porte(joueur, BOTTES)) {
            event.setDistance(0);
            event.setDamageMultiplier(0);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        GRAPPIN_PRET.clear();
        SAUTEES.clear();
        BULLES.clear();
        GRAPPINS.clear();
    }

    private Espionne() {
    }
}
