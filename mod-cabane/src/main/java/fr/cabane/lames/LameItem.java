package fr.cabane.lames;

import fr.cabane.Cabane;
import fr.cabane.lames.Lames.Sorte;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * Une lame de la Brume : une épée (avec le balayage) à ses dégâts, sa vitesse et sa portée ; incassable ; son coup
 * spécial au clic droit (voir {@link Lames}), puis sa recharge. Kiba électrise ce qu'elle touche, Shibuki explose un
 * coup sur trois, Kabutowari casse les boucliers comme une hache.
 */
public class LameItem extends Item {
    private static final net.minecraft.resources.ResourceLocation PORTEE = Cabane.id("lame_portee");
    public final Sorte sorte;

    public LameItem(Sorte sorte, Item.Properties p) {
        super(p.attributes(attributs(sorte)));
        this.sorte = sorte;
    }

    private static ItemAttributeModifiers attributs(Sorte s) {
        var b = ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, s.degats - 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, s.vitesse - 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        if (s.portee > 0) {
            b.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(PORTEE, s.portee, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        return b.build();
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility action) {
        return ItemAbilities.DEFAULT_SWORD_ACTIONS.contains(action);
    }

    @Override
    public boolean canDisableShield(ItemStack stack, ItemStack shield, LivingEntity entity, LivingEntity attacker) {
        return sorte == Sorte.KABUTOWARI;
    }

    // ------------------------------------------------------------------ les coups

    /** Les coups de Shibuki, pour l'explosion d'un coup sur trois. */
    private static final Map<UUID, Integer> COUPS = new HashMap<>();
    private static final DustParticleOptions BLEU = new DustParticleOptions(new org.joml.Vector3f(0.45F, 0.85F, 1F), 1.2F);

    /** Le combo de chaque joueur côté serveur : combien de coups d'affilée, et quand le dernier. */
    private static final Map<UUID, int[]> COMBO = new HashMap<>();
    private static final DustParticleOptions SANG = new DustParticleOptions(new org.joml.Vector3f(0.55F, 0.02F, 0.02F), 1.1F);

    /** Le coup du combo qu'on vient de donner (0, 1, 2…) : les coups qui se suivent à moins de 1,5 s s'enchaînent. */
    private int coupDuCombo(LivingEntity attaquant) {
        int maintenant = attaquant.tickCount;
        int[] c = COMBO.computeIfAbsent(attaquant.getUUID(), k -> new int[]{-1, -100});
        c[0] = maintenant - c[1] <= 30 ? c[0] + 1 : 0;
        c[1] = maintenant;
        return c[0] % (sorte == Sorte.KIBA ? 4 : 3);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity cible, LivingEntity attaquant) {
        if (!(attaquant.level() instanceof ServerLevel level)) {
            return true;
        }
        // l'impact : le coup qui tranche, le sang ; le dernier coup du combo projette et soulève la terre
        int coup = coupDuCombo(attaquant);
        if (attaquant instanceof ServerPlayer jp) {
            Zones.frappee(jp, cible);
            int c = Zones.coupEnCours(jp, sorte);
            if (c >= 0) {
                coup = c;
            }
        }
        boolean fin = coup == (sorte == Sorte.KIBA ? 3 : 2);
        double y = cible.getY(0.55);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, cible.getX(), y, cible.getZ(), 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CRIT, cible.getX(), y, cible.getZ(), fin ? 24 : 10, 0.3, 0.4, 0.3, 0.45);
        level.sendParticles(SANG, cible.getX(), y, cible.getZ(), fin ? 22 : 10, 0.25, 0.35, 0.25, 0);
        if (fin) {
            Vec3 loin = cible.position().subtract(attaquant.position()).normalize();
            cible.setDeltaMovement(cible.getDeltaMovement().add(loin.x * 0.9, 0.35, loin.z * 0.9));
            cible.hurtMarked = true;
            var sol = level.getBlockState(cible.blockPosition().below());
            if (!sol.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sol), cible.getX(), cible.getY() + 0.1, cible.getZ(), 40, 0.6, 0.1, 0.6, 0.2);
            }
            level.playSound(null, cible.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2F, 0.7F);
        }
        switch (sorte) {
            case KIBA -> {
                cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 0));
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cible.getX(), cible.getY(0.6), cible.getZ(), 14, 0.3, 0.4, 0.3, 0.3);
            }
            case SHIBUKI -> {
                int n = COUPS.merge(attaquant.getUUID(), 1, Integer::sum);
                if (n >= 3 && attaquant instanceof ServerPlayer j) {
                    COUPS.put(attaquant.getUUID(), 0);
                    Lames.boom(level, j, cible.position().add(0, cible.getBbHeight() * 0.5, 0), 1.4F, 4);
                }
            }
            case SAMEHADA -> level.sendParticles(BLEU, cible.getX(), cible.getY(0.6), cible.getZ(), 10, 0.3, 0.4, 0.3, 0);
            default -> {
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ le clic droit

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
        ItemStack stack = joueur.getItemInHand(main);
        if (main != InteractionHand.MAIN_HAND || joueur.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.pass(stack);
        }
        if (sorte == Sorte.HIRAMEKAREI) {
            joueur.startUsingItem(main);
            return InteractionResultHolder.consume(stack);
        }
        // pas de geste du bras ici : c'est l'animation du clic droit (que le serveur annonce à tous) qui le fait
        if (level.isClientSide || !(joueur instanceof ServerPlayer j)) {
            return InteractionResultHolder.consume(stack);
        }
        ServerLevel monde = j.serverLevel();
        boolean recrache = sorte == Sorte.SAMEHADA && Lames.aQuelquUn(j);
        // chaque effet part au moment où l'animation le lance (le bras qui lance, les lames levées, la lame au sol...)
        boolean fait = switch (sorte) {
            case KUBIKIRIBOCHO -> {
                if (stack.has(Lames.LANCEE.get())) {
                    yield false;
                }
                Lames.plusTard(j, sorte, 9, () -> lancer(monde, j, j.getMainHandItem()));
                yield true;
            }
            case SAMEHADA -> avaler(j, stack);
            case KIBA -> {
                Lames.plusTard(j, sorte, 10, () -> foudre(monde, j));
                yield true;
            }
            case NUIBARI -> {
                Lames.plusTard(j, sorte, 5, () -> coudre(monde, j));
                yield true;
            }
            case SHIBUKI -> {
                Lames.plusTard(j, sorte, 10, () -> deflagration(monde, j));
                yield true;
            }
            case KABUTOWARI -> {
                Lames.sauter(j);
                yield true;
            }
            default -> false;
        };
        if (!fait) {
            return InteractionResultHolder.fail(stack);
        }
        if (sorte != Sorte.SAMEHADA) {
            j.getCooldowns().addCooldown(this, sorte.recharge);
        }
        if (!recrache) {
            Lames.annoncer(j, sorte, 0);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** Kubikiribôchô : elle part en tournoyant, coupe tout sur son chemin et revient dans la main. */
    private static boolean lancer(ServerLevel monde, ServerPlayer j, ItemStack stack) {
        if (stack.has(Lames.LANCEE.get())) {
            return false;
        }
        monde.addFreshEntity(new LameLancee(monde, j));
        stack.set(Lames.LANCEE.get(), (int) monde.getGameTime());
        monde.playSound(null, j.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.2F, 0.55F);
        return true;
    }

    /** Samehada : elle avale celui qu'on vise (5 blocs), ou recrache celui qu'elle a dedans. */
    private static boolean avaler(ServerPlayer j, ItemStack stack) {
        if (Lames.aQuelquUn(j)) {
            Lames.recracher(j);
            return true;
        }
        Entity vise = viser(j, 5);
        return vise instanceof LivingEntity proie && Lames.avaler(j, proie, stack);
    }

    /** Kiba : la foudre tombe là où l'on vise (32 blocs). */
    private static boolean foudre(ServerLevel monde, ServerPlayer j) {
        Vec3 oeil = j.getEyePosition(), fin = oeil.add(j.getLookAngle().scale(32));
        HitResult bloc = monde.clip(new ClipContext(oeil, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        if (bloc.getType() != HitResult.Type.MISS) {
            fin = bloc.getLocation();
        }
        Entity vise = viser(j, oeil.distanceTo(fin));
        Vec3 ou = vise != null ? vise.position() : fin;
        LightningBolt eclair = EntityType.LIGHTNING_BOLT.create(monde);
        if (eclair != null) {
            eclair.moveTo(ou.x, ou.y, ou.z);
            eclair.setVisualOnly(true);
            monde.addFreshEntity(eclair);
        }
        monde.playSound(null, ou.x, ou.y, ou.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 2F, 1.1F);
        monde.playSound(null, ou.x, ou.y, ou.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 2F, 1F);
        monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, ou.x, ou.y + 0.5, ou.z, 60, 1.2, 0.8, 1.2, 0.4);
        // les arcs entre les deux lames, au-dessus de sa tête
        monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, j.getX(), j.getY() + 2.6, j.getZ(), 25, 0.4, 0.3, 0.4, 0.2);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(ou, ou).inflate(2.5, 3, 2.5))) {
            if (e != j && e.isAlive() && e.distanceToSqr(ou) < 2.5 * 2.5 + 4) {
                e.hurt(monde.damageSources().playerAttack(j), 10 * Zones.force(j));
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
            }
        }
        return true;
    }

    /** Nuibari : l'aiguille traverse jusqu'à trois ennemis alignés, et le fil les ramène vers soi. */
    private static boolean coudre(ServerLevel monde, ServerPlayer j) {
        Vec3 oeil = j.getEyePosition(), vue = j.getLookAngle(), fin = oeil.add(vue.scale(24));
        HitResult bloc = monde.clip(new ClipContext(oeil, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        if (bloc.getType() != HitResult.Type.MISS) {
            fin = bloc.getLocation();
        }
        Vec3 a = oeil, b = fin;
        List<LivingEntity> cousus = new ArrayList<>(monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(1),
                e -> e != j && e.isAlive() && !e.isSpectator() && e.getBoundingBox().inflate(0.4).clip(a, b).isPresent()));
        cousus.sort(Comparator.comparingDouble(e -> e.distanceToSqr(oeil)));
        if (cousus.size() > 3) {
            cousus = new ArrayList<>(cousus.subList(0, 3));
        }
        for (LivingEntity e : cousus) {
            e.hurt(monde.damageSources().playerAttack(j), 6 * Zones.force(j));
            Vec3 vers = j.position().subtract(e.position()).normalize();
            e.setDeltaMovement(vers.x * 1.5, 0.35, vers.z * 1.5);
            e.hurtMarked = true;
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            monde.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY(0.6), e.getZ(), 10, 0.2, 0.3, 0.2, 0.2);
        }
        Vec3 bout = cousus.isEmpty() ? fin : cousus.getLast().position().add(0, 1, 0).add(vue.scale(1.5));
        Lames.coudre(j, cousus, bout);
        monde.playSound(null, j.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 1.2F, 0.5F);
        monde.playSound(null, j.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
        return true;
    }

    /** Shibuki : la lame frappe le sol et trois explosions partent devant, de plus en plus grosses. */
    private static boolean deflagration(ServerLevel monde, ServerPlayer j) {
        Vec3 vue = j.getLookAngle(), dir = new Vec3(vue.x, 0, vue.z);
        dir = dir.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, j.getYRot()) : dir.normalize();
        Vec3 pied = j.position();
        Lames.exploser(j, pied.add(dir.scale(2.6)).add(0, 0.5, 0), 1.7F, 8, 0);
        Lames.exploser(j, pied.add(dir.scale(4.2)).add(0, 0.7, 0), 2.2F, 9, 3);
        Lames.exploser(j, pied.add(dir.scale(6)).add(0, 0.9, 0), 2.7F, 10, 6);
        monde.playSound(null, j.blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1F, 1.3F);
        return true;
    }

    /** Kabutowari : à la réception du saut, une onde de choc qui sonne tout le monde et abîme les casques. */
    static void ecraser(ServerPlayer j) {
        ServerLevel monde = j.serverLevel();
        Vec3 ou = j.position();
        monde.sendParticles(ParticleTypes.EXPLOSION, ou.x, ou.y + 0.3, ou.z, 4, 1.5, 0.1, 1.5, 0);
        var sol = monde.getBlockState(j.blockPosition().below());
        if (!sol.isAir()) {
            monde.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sol), ou.x, ou.y + 0.2, ou.z, 120, 2.5, 0.3, 2.5, 0.25);
        }
        monde.playSound(null, j.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.4F, 0.6F);
        monde.playSound(null, j.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1F, 0.7F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, j.getBoundingBox().inflate(5, 2, 5))) {
            double d = e.distanceTo(j);
            if (e == j || !e.isAlive() || d > 5.5) {
                continue;
            }
            e.hurt(monde.damageSources().playerAttack(j), (float) (9 * (1 - d / 7)) * Zones.force(j));
            Vec3 loin = e.position().subtract(ou).normalize();
            e.setDeltaMovement(loin.x * 0.6, 0.7, loin.z * 0.6);
            e.hurtMarked = true;
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            e.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0));
            ItemStack casque = e.getItemBySlot(EquipmentSlot.HEAD);
            if (casque.isDamageableItem()) {
                casque.hurtAndBreak(Math.max(1, casque.getMaxDamage() / 3), e, EquipmentSlot.HEAD);
            }
        }
    }

    /** Ce que le joueur vise (une créature vivante), jusqu'à cette distance. */
    static Entity viser(ServerPlayer j, double distance) {
        Vec3 oeil = j.getEyePosition(), fin = oeil.add(j.getLookAngle().scale(distance));
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(j.level(), j, oeil, fin, new AABB(oeil, fin).inflate(1),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator());
        return touche == null ? null : touche.getEntity();
    }

    // ------------------------------------------------------------------ Hiramekarei : on charge, on relâche

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entite) {
        return sorte == Sorte.HIRAMEKAREI ? 72000 : 0;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return sorte == Sorte.HIRAMEKAREI ? UseAnim.CUSTOM : UseAnim.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entite, ItemStack stack, int reste) {
        if (level instanceof ServerLevel monde && (getUseDuration(stack, entite) - reste) % 3 == 0) {
            monde.sendParticles(BLEU, entite.getX(), entite.getY() + 2.2, entite.getZ(), 4, 0.3, 0.5, 0.3, 0);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entite, int reste) {
        int tenu = getUseDuration(stack, entite) - reste;
        if (tenu < 10 || !(entite instanceof ServerPlayer j)) {
            return;
        }
        float charge = Math.min(1, tenu / 40F);
        level.addFreshEntity(new LameChakra(level, j, charge));
        j.getCooldowns().addCooldown(this, sorte.recharge);
        Lames.annoncer(j, sorte, 18);
        level.playSound(null, j.blockPosition(), SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.3F, 0.7F);
    }

    // ------------------------------------------------------------------ le reste

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entite, int slot, boolean tenue) {
        // une Kubikiribôchô restée « en vol » (le joueur a déconnecté pendant le lancer) revient d'elle-même
        Integer depuis = stack.get(Lames.LANCEE.get());
        if (!level.isClientSide && depuis != null && level.getGameTime() - depuis > 240) {
            stack.remove(Lames.LANCEE.get());
        }
        Integer avale = stack.get(Lames.AVALE.get());
        if (!level.isClientSide && avale != null && entite instanceof ServerPlayer j && !Lames.aQuelquUn(j)) {
            stack.remove(Lames.AVALE.get());
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lignes, TooltipFlag flag) {
        lignes.add(Component.translatable("item.cabane." + sorte.id + ".qui").withStyle(ChatFormatting.GRAY));
        lignes.add(Component.keybind("key.use").append(" : ").append(Component.translatable("item.cabane." + sorte.id + ".special"))
                .withStyle(ChatFormatting.AQUA));
    }
}
