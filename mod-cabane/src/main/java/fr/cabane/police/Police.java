package fr.cabane.police;

import fr.cabane.Cabane;
import fr.cabane.hub.Gardes;
import io.netty.buffer.ByteBuf;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * La police de Beauvais : les menottes, le pistolet (il ne blesse personne), la tenue et la voiture.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Police {
    public static final DeferredRegister<ArmorMaterial> MATERIAUX = DeferredRegister.create(Registries.ARMOR_MATERIAL, Cabane.MODID);

    /** Un uniforme, pas une armure : à peine mieux que rien. */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> UNIFORME = MATERIAUX.register("police", () -> new ArmorMaterial(
            Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
                m.put(ArmorItem.Type.BOOTS, 1);
                m.put(ArmorItem.Type.LEGGINGS, 1);
                m.put(ArmorItem.Type.CHESTPLATE, 2);
                m.put(ArmorItem.Type.HELMET, 1);
                m.put(ArmorItem.Type.BODY, 2);
            }),
            10, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.BLUE_WOOL),
            List.of(new ArmorMaterial.Layer(Cabane.id("police"))), 0F, 0F));

    public static void register(IEventBus modBus) {
        MATERIAUX.register(modBus);
        modBus.addListener(Police::registerPayloads);
    }

    // ------------------------------------------------------------------ la tenue

    /** Clic droit : on enfile toute la tenue d'un coup (ce qu'on portait part dans l'inventaire). */
    public static class TenueItem extends Item {
        public TenueItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            ItemStack tenue = joueur.getItemInHand(main);
            if (!level.isClientSide) {
                habiller(joueur, net.minecraft.world.entity.EquipmentSlot.HEAD, fr.cabane.CabaneItems.CASQUETTE_POLICE.get());
                habiller(joueur, net.minecraft.world.entity.EquipmentSlot.CHEST, fr.cabane.CabaneItems.VESTE_POLICE.get());
                habiller(joueur, net.minecraft.world.entity.EquipmentSlot.LEGS, fr.cabane.CabaneItems.PANTALON_POLICE.get());
                habiller(joueur, net.minecraft.world.entity.EquipmentSlot.FEET, fr.cabane.CabaneItems.CHAUSSURES_POLICE.get());
                level.playSound(null, joueur.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1F, 1F);
                joueur.displayClientMessage(Component.literal("👮 Police de Beauvais, en service !").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD), true);
                tenue.consume(1, joueur);
            }
            return InteractionResultHolder.sidedSuccess(tenue, level.isClientSide);
        }

        private static void habiller(Player joueur, net.minecraft.world.entity.EquipmentSlot place, Item piece) {
            ItemStack avant = joueur.getItemBySlot(place);
            if (!avant.isEmpty() && !joueur.getInventory().add(avant)) {
                joueur.drop(avant, false);
            }
            joueur.setItemSlot(place, new ItemStack(piece));
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Clic droit : enfiler toute la tenue").withStyle(ChatFormatting.GRAY));
        }
    }

    // ------------------------------------------------------------------ la matraque

    /**
     * Un coup de matraque sur un menotté : on le charge sur son dos (pour l'emmener en prison). Encore un coup, ou
     * clic droit accroupi avec la matraque : on le pose.
     */
    public static class MatraqueItem extends net.minecraft.world.item.SwordItem {
        public MatraqueItem(Properties properties) {
            super(net.minecraft.world.item.Tiers.IRON, properties);
        }

        @Override
        public boolean hurtEnemy(ItemStack stack, LivingEntity cible, LivingEntity agent) {
            if (cible instanceof ServerPlayer prisonnier && agent instanceof ServerPlayer policier && menotte(prisonnier)) {
                if (prisonnier.getVehicle() == policier) {
                    poser(prisonnier);
                } else {
                    prisonnier.stopRiding();
                    prisonnier.startRiding(policier, true);
                    prisonnier.serverLevel().playSound(null, prisonnier.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1F, 0.7F);
                    policier.displayClientMessage(Component.literal("Tu portes " + prisonnier.getGameProfile().getName()
                            + " sur ton dos. (accroupi + clic droit avec la matraque pour le poser)").withStyle(ChatFormatting.BLUE), true);
                    prisonnier.displayClientMessage(Component.literal("👮 Tu es emmené sur le dos de " + policier.getGameProfile().getName() + ".").withStyle(ChatFormatting.RED), true);
                }
            }
            return super.hurtEnemy(stack, cible, agent);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            if (!level.isClientSide && joueur.isShiftKeyDown()) {
                for (net.minecraft.world.entity.Entity passager : List.copyOf(joueur.getPassengers())) {
                    if (passager instanceof ServerPlayer prisonnier) {
                        poser(prisonnier);
                    }
                }
            }
            return super.use(level, joueur, main);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Frapper un menotté : le charger sur son dos").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal("Accroupi + clic droit : le poser").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static final java.util.Set<UUID> POSES = new java.util.HashSet<>();

    private static void poser(ServerPlayer prisonnier) {
        POSES.add(prisonnier.getUUID());
        prisonnier.stopRiding();
        POSES.remove(prisonnier.getUUID());
        prisonnier.displayClientMessage(Component.literal("On te pose par terre.").withStyle(ChatFormatting.GRAY), true);
    }

    /** Un menotté porté sur le dos ne peut pas descendre tout seul. */
    @SubscribeEvent
    public static void onDescente(net.neoforged.neoforge.event.entity.EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityMounting() instanceof ServerPlayer prisonnier && menotte(prisonnier)
                && event.getEntityBeingMounted() instanceof Player && !POSES.contains(prisonnier.getUUID()) && prisonnier.isAlive()) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ le pistolet

    /** Munitions infinies, une balle toutes les 0,7 s. Ne blesse pas : la cible brille et ralentit. */
    public static class PistoletItem extends Item {
        private static final double PORTEE = 48;
        private static final int CADENCE = 14;

        public PistoletItem(Properties properties) {
            super(properties.stacksTo(1));
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            ItemStack pistolet = joueur.getItemInHand(main);
            if (level instanceof ServerLevel serveur && joueur instanceof ServerPlayer tireur) {
                tirer(serveur, tireur);
                tireur.getCooldowns().addCooldown(this, CADENCE);
            }
            return InteractionResultHolder.consume(pistolet);
        }

        private static void tirer(ServerLevel level, ServerPlayer tireur) {
            Vec3 oeil = tireur.getEyePosition();
            Vec3 regard = tireur.getViewVector(1F);
            Vec3 fin = oeil.add(regard.scale(PORTEE));
            BlockHitResult mur = level.clip(new ClipContext(oeil, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, tireur));
            if (mur.getType() != HitResult.Type.MISS) {
                fin = mur.getLocation();
            }
            EntityHitResult touche = ProjectileUtil.getEntityHitResult(level, tireur, oeil, fin, new AABB(oeil, fin).inflate(1),
                    e -> e instanceof LivingEntity && e != tireur && !e.isSpectator());
            if (touche != null) {
                fin = touche.getLocation();
            }
            // la trace de la balle, depuis le canon
            Vec3 canon = oeil.add(regard.scale(0.6)).add(regard.cross(new Vec3(0, 1, 0)).normalize().scale(-0.25)).add(0, -0.15, 0);
            double longueur = canon.distanceTo(fin);
            for (double d = 0.4; d < longueur; d += 0.6) {
                Vec3 p = canon.add(fin.subtract(canon).normalize().scale(d));
                level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
            level.sendParticles(ParticleTypes.SMOKE, canon.x, canon.y, canon.z, 3, 0.03, 0.03, 0.03, 0.01);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, fin.x, fin.y, fin.z, 6, 0.08, 0.08, 0.08, 0.15);
            level.playSound(null, tireur.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.45F, 1.9F);
            level.playSound(null, tireur.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.25F, 1.6F);
            if (touche != null && touche.getEntity() instanceof LivingEntity cible) {
                cible.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
                cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1, false, false));
                cible.knockback(0.25, -regard.x, -regard.z);
                level.playSound(null, cible.blockPosition(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.8F, 1.5F);
                tireur.displayClientMessage(Component.literal("Touché !").withStyle(ChatFormatting.YELLOW), true);
                if (cible.getTags().contains(Gardes.MACRON) || cible.getTags().contains(Gardes.GARDE)) {
                    Gardes.lacher(tireur);
                }
            }
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Munitions infinies").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal("Ne blesse personne : la cible brille et ralentit.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ------------------------------------------------------------------ les menottes

    private static final String MENOTTES = "cabane_menottes";
    private static final String DEPUIS = "cabane_menottes_depuis";
    /** Au bout de dix minutes, on finit toujours par se libérer. */
    private static final long LIBERATION = 20 * 60 * 10;

    public static boolean menotte(Player joueur) {
        return joueur.getPersistentData().hasUUID(MENOTTES);
    }

    /** Clic droit sur un joueur : on le menotte ; encore une fois : on le libère. */
    public static class MenottesItem extends Item {
        public MenottesItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult interactLivingEntity(ItemStack stack, Player agent, LivingEntity cible, InteractionHand main) {
            if (!(cible instanceof ServerPlayer prisonnier) || !(agent instanceof ServerPlayer policier)) {
                return cible instanceof Player ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (menotte(prisonnier)) {
                liberer(prisonnier);
                policier.displayClientMessage(Component.literal("Tu as retiré les menottes de " + prisonnier.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN), true);
            } else {
                CompoundTag data = prisonnier.getPersistentData();
                data.putUUID(MENOTTES, policier.getUUID());
                data.putLong(DEPUIS, prisonnier.serverLevel().getGameTime());
                prisonnier.serverLevel().playSound(null, prisonnier.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1F, 1.2F);
                prisonnier.displayClientMessage(Component.literal("🔒 " + policier.getGameProfile().getName() + " t'a passé les menottes !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
                policier.displayClientMessage(Component.literal("🔒 " + prisonnier.getGameProfile().getName() + " est menotté. Il te suit.").withStyle(ChatFormatting.BLUE), true);
            }
            return InteractionResult.SUCCESS;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Clic droit sur un joueur : menotter / libérer").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void liberer(ServerPlayer prisonnier) {
        prisonnier.getPersistentData().remove(MENOTTES);
        prisonnier.getPersistentData().remove(DEPUIS);
        prisonnier.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        prisonnier.removeEffect(MobEffects.DIG_SLOWDOWN);
        prisonnier.removeEffect(MobEffects.WEAKNESS);
        prisonnier.serverLevel().playSound(null, prisonnier.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1F, 1.2F);
        prisonnier.displayClientMessage(Component.literal("🔓 Tu es libre.").withStyle(ChatFormatting.GREEN), true);
    }

    /** Menotté : on avance à peine, on ne frappe pas, et on suit son policier comme en laisse. */
    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer prisonnier) || !menotte(prisonnier)) {
            return;
        }
        CompoundTag data = prisonnier.getPersistentData();
        if (prisonnier.serverLevel().getGameTime() - data.getLong(DEPUIS) > LIBERATION) {
            liberer(prisonnier);
            return;
        }
        if (prisonnier.tickCount % 10 == 0) {
            prisonnier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 2, true, false));
            prisonnier.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 30, 4, true, false));
            prisonnier.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 4, true, false));
        }
        UUID id = data.getUUID(MENOTTES);
        ServerPlayer policier = prisonnier.server.getPlayerList().getPlayer(id);
        if (policier == null || policier.level() != prisonnier.level() || prisonnier.isPassenger()) {
            return;
        }
        double distance = prisonnier.distanceTo(policier);
        if (distance > 20) {
            prisonnier.teleportTo(policier.getX(), policier.getY(), policier.getZ());
        } else if (distance > 3.5) {
            Vec3 tire = policier.position().subtract(prisonnier.position()).normalize().scale(Math.min(0.6, 0.12 * (distance - 3)));
            prisonnier.setDeltaMovement(prisonnier.getDeltaMovement().add(tire.x, Math.max(0, tire.y) + (prisonnier.horizontalCollision ? 0.3 : 0), tire.z));
            prisonnier.hurtMarked = true;
        }
    }

    private static boolean bloquer(Player joueur) {
        if (!menotte(joueur)) {
            return false;
        }
        joueur.displayClientMessage(Component.literal("🔒 Tu es menotté !").withStyle(ChatFormatting.RED), true);
        return true;
    }

    @SubscribeEvent
    public static void onAttaque(AttackEntityEvent event) {
        if (bloquer(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClicObjet(PlayerInteractEvent.RightClickItem event) {
        if (bloquer(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClicBloc(PlayerInteractEvent.RightClickBlock event) {
        if (bloquer(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Le crochetage en cours : qui force les menottes de qui, depuis combien de ticks. */
    private record Crochetage(UUID prisonnier, int avance, long dernier) {
    }

    private static final java.util.Map<UUID, Crochetage> CROCHETAGES = new java.util.HashMap<>();
    /** Sept secondes de clic droit maintenu pour forcer des menottes. */
    private static final int DUREE_CROCHETAGE = 140;

    @SubscribeEvent
    public static void onClicEntite(PlayerInteractEvent.EntityInteract event) {
        if (bloquer(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        // forcer les menottes d'un autre : rester en clic droit sur lui (sans menottes en main)
        if (!(event.getEntity() instanceof ServerPlayer aide) || !(event.getTarget() instanceof ServerPlayer prisonnier) || !menotte(prisonnier)
                || event.getHand() != InteractionHand.MAIN_HAND || event.getItemStack().getItem() instanceof MenottesItem) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        long maintenant = aide.serverLevel().getGameTime();
        Crochetage c = CROCHETAGES.get(aide.getUUID());
        int avance = c != null && c.prisonnier().equals(prisonnier.getUUID()) && maintenant - c.dernier() <= 10
                ? c.avance() + (int) (maintenant - c.dernier()) : 0;
        CROCHETAGES.put(aide.getUUID(), new Crochetage(prisonnier.getUUID(), avance, maintenant));
        if (avance >= DUREE_CROCHETAGE) {
            CROCHETAGES.remove(aide.getUUID());
            if (prisonnier.getVehicle() instanceof Player) {
                poser(prisonnier);
            }
            liberer(prisonnier);
            aide.displayClientMessage(Component.literal("🔓 Les menottes ont cédé !").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
            prisonnier.displayClientMessage(Component.literal("🔓 " + aide.getGameProfile().getName() + " a forcé tes menottes !").withStyle(ChatFormatting.GREEN), true);
            return;
        }
        int pct = avance * 100 / DUREE_CROCHETAGE;
        String barre = "▮".repeat(pct / 10) + "▯".repeat(10 - pct / 10);
        aide.displayClientMessage(Component.literal("🔓 Tu forces les menottes… " + barre + " " + pct + " % (reste en clic droit)").withStyle(ChatFormatting.YELLOW), true);
        prisonnier.displayClientMessage(Component.literal("🔓 " + aide.getGameProfile().getName() + " force tes menottes… " + barre).withStyle(ChatFormatting.YELLOW), true);
        if (avance % 20 < 5) {
            aide.serverLevel().playSound(null, prisonnier.blockPosition(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 0.8F, 0.8F + aide.getRandom().nextFloat() * 0.4F);
        }
    }

    @SubscribeEvent
    public static void onCasse(BlockEvent.BreakEvent event) {
        if (bloquer(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ la voiture

    /** Poser la voiture de police devant soi. */
    public static class VoitureItem extends Item {
        public VoitureItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            if (!level.isClientSide) {
                Vec3 p = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
                VoitureEntity voiture = new VoitureEntity(fr.cabane.CabaneEntities.VOITURE_POLICE.get(), level);
                float yaw = context.getPlayer() != null ? context.getPlayer().getYRot() : 0F;
                voiture.moveTo(p.x, p.y, p.z, yaw, 0F);
                if (!level.noCollision(voiture, voiture.getBoundingBox())) {
                    if (context.getPlayer() != null) {
                        context.getPlayer().displayClientMessage(Component.literal("Pas assez de place pour garer la voiture ici.").withStyle(ChatFormatting.RED), true);
                    }
                    return InteractionResult.FAIL;
                }
                level.addFreshEntity(voiture);
                context.getItemInHand().consume(1, context.getPlayer());
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Pas besoin d'essence. ZQSD pour conduire, H pour la sirène.").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal("Accroupi + frapper : la ranger.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Le conducteur allume ou coupe la sirène. */
    public record Sirene() implements CustomPacketPayload {
        public static final Type<Sirene> TYPE = new Type<>(Cabane.id("sirene"));
        public static final StreamCodec<ByteBuf, Sirene> CODEC = StreamCodec.unit(new Sirene());

        @Override
        public Type<Sirene> type() {
            return TYPE;
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Sirene.TYPE, Sirene.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player().getVehicle() instanceof VoitureEntity voiture && voiture.getControllingPassenger() == ctx.player()) {
                voiture.basculerSirene();
            }
        }));
    }

    private Police() {
    }
}
