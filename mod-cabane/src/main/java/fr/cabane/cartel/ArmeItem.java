package fr.cabane.cartel;

import fr.cabane.Cabane;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Une arme du cartel. Clic droit : on tire (maintenu pour les armes automatiques). Les munitions sont infinies, mais
 * le chargeur se vide : R (ou un chargeur vide) recharge, et pendant la recharge l'arme est grisée comme un objet en
 * attente. Les balles touchent tout de suite (pas de projectile) ; la roquette et l'obus volent, et leurs explosions
 * blessent sans casser aucun bloc. Les grenades et les cocktails se lancent (voir {@link ProjectileArme}).
 */
public class ArmeItem extends Item {
    public static final ResourceKey<DamageType> BALLE = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("balle"));

    public enum Tir { BALLE, ROQUETTE, OBUS, GRENADE, MOLOTOV }

    /** Comment on la tient : fusil (deux mains, à l'épaule), épaule (le bazooka dessus), hanche (le minigun), main (une seule). */
    public enum Prise { FUSIL, EPAULE, HANCHE, MAIN, LANCER }

    /**
     * degats : par balle (2 = un cœur) ; cadence : ticks entre deux tirs ; portee : en blocs ; chargeur : balles par
     * chargeur ; recharge : ticks ; dispersion : en degrés ; plombs : balles par tir (le canon scié) ; puissance : celle
     * de l'explosion ; lourd : ralentit quand on tire ; lunette : on vise accroupi.
     */
    public record Spec(float degats, int cadence, double portee, int chargeur, int recharge, float dispersion, int plombs,
                       boolean auto, boolean lourd, boolean lunette, Tir tir, float puissance, Prise prise, Supplier<SoundEvent> son) {
        static Spec balle(float degats, int cadence, double portee, int chargeur, int recharge, float dispersion, boolean auto, Prise prise,
                          Supplier<SoundEvent> son) {
            return new Spec(degats, cadence, portee, chargeur, recharge, dispersion, 1, auto, false, false, Tir.BALLE, 0, prise, son);
        }

        Spec plombs(int n) {
            return new Spec(degats, cadence, portee, chargeur, recharge, dispersion, n, auto, lourd, lunette, tir, puissance, prise, son);
        }

        Spec lourde() {
            return new Spec(degats, cadence, portee, chargeur, recharge, dispersion, plombs, auto, true, lunette, tir, puissance, prise, son);
        }

        Spec avecLunette() {
            return new Spec(degats, cadence, portee, chargeur, recharge, dispersion, plombs, auto, lourd, true, tir, puissance, prise, son);
        }
    }

    public final Spec spec;

    public ArmeItem(Properties properties, Spec spec) {
        super(properties);
        this.spec = spec;
    }

    public boolean deuxMains() {
        return spec.prise() == Prise.FUSIL || spec.prise() == Prise.EPAULE || spec.prise() == Prise.HANCHE;
    }

    public int munitions(ItemStack stack) {
        return stack.getOrDefault(Cartel.MUNITIONS.get(), spec.chargeur());
    }

    // ------------------------------------------------------------------ tirer

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
        ItemStack stack = joueur.getItemInHand(main);
        if (joueur.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        if (spec.tir() == Tir.GRENADE || spec.tir() == Tir.MOLOTOV) {
            if (level instanceof ServerLevel serveur) {
                ProjectileArme.lancer(serveur, joueur, spec.tir());
                stack.consume(1, joueur);
                joueur.getCooldowns().addCooldown(this, spec.cadence());
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (spec.auto()) {
            joueur.startUsingItem(main);
            return InteractionResultHolder.consume(stack);
        }
        if (level instanceof ServerLevel serveur && joueur instanceof ServerPlayer tireur) {
            tirer(serveur, tireur, stack);
        }
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entite) {
        return spec.auto() ? 72000 : 0;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /** L'arme automatique tire tant qu'on garde le clic droit, au rythme de sa cadence. */
    @Override
    public void onUseTick(Level level, LivingEntity entite, ItemStack stack, int reste) {
        if (level instanceof ServerLevel serveur && entite instanceof ServerPlayer tireur && (72000 - reste) % spec.cadence() == 0) {
            if (tireur.getCooldowns().isOnCooldown(this)) {
                tireur.stopUsingItem();
                return;
            }
            tirer(serveur, tireur, stack);
        }
    }

    void tirer(ServerLevel level, ServerPlayer tireur, ItemStack stack) {
        int balles = munitions(stack);
        if (balles <= 0) {
            level.playSound(null, tireur.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
            recharger(level, tireur, stack);
            return;
        }
        stack.set(Cartel.MUNITIONS.get(), balles - 1);
        Vec3 oeil = tireur.getEyePosition();
        Vec3 regard = tireur.getViewVector(1F);
        Vec3 canon = bouche(tireur, oeil, regard);
        level.playSound(null, canon.x, canon.y, canon.z, spec.son().get(), SoundSource.PLAYERS, spec.tir() == Tir.BALLE ? 3F : 4F,
                0.94F + tireur.getRandom().nextFloat() * 0.12F);
        switch (spec.tir()) {
            case ROQUETTE, OBUS -> ProjectileArme.tirer(level, tireur, canon, regard, spec);
            default -> {
                // chaque balle part exactement là où on vise ; seuls les plombs du canon scié s'ouvrent un peu autour du viseur
                float dispersion = spec.plombs() > 1 ? spec.dispersion() : 0F;
                for (int i = 0; i < spec.plombs(); i++) {
                    balle(level, tireur, oeil, canon, devier(regard, dispersion, tireur), spec);
                }
            }
        }
        if (spec.lourd()) {
            tireur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 8, 1, false, false));
        }
        if (balles - 1 <= 0) {
            tireur.stopUsingItem();
            recharger(level, tireur, stack);
        } else if (!spec.auto()) {
            // le coup par coup : on attend la cadence avant le suivant
            tireur.getCooldowns().addCooldown(this, spec.cadence());
        }
    }

    /** Remplit le chargeur ; l'arme reste grisée le temps de la recharge. */
    public void recharger(ServerLevel level, ServerPlayer tireur, ItemStack stack) {
        if (spec.chargeur() <= 0 || tireur.getCooldowns().isOnCooldown(this) || munitions(stack) >= spec.chargeur()) {
            return;
        }
        tireur.stopUsingItem();
        stack.set(Cartel.MUNITIONS.get(), spec.chargeur());
        tireur.getCooldowns().addCooldown(this, spec.recharge());
        level.playSound(null, tireur.blockPosition(), SoundEvents.CROSSBOW_LOADING_START.value(), SoundSource.PLAYERS, 0.8F, 1.3F);
        level.playSound(null, tireur.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.9F, 1.4F);
        tireur.displayClientMessage(Component.literal("Rechargement…").withStyle(ChatFormatting.GRAY), true);
    }

    /** Le bout du canon : les armes sont longues, il est bien devant, un peu à droite et sous les yeux. */
    static Vec3 bouche(LivingEntity tireur, Vec3 oeil, Vec3 regard) {
        Vec3 droite = regard.cross(new Vec3(0, 1, 0));
        droite = droite.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : droite.normalize();
        return oeil.add(regard.scale(1.6)).add(droite.scale(0.3)).add(0, -0.22, 0);
    }

    static Vec3 devier(Vec3 regard, float degres, Entity tireur) {
        if (degres <= 0) {
            return regard;
        }
        double r = Math.toRadians(degres);
        return regard.add(tireur.getRandom().nextGaussian() * r * 0.6, tireur.getRandom().nextGaussian() * r * 0.6,
                tireur.getRandom().nextGaussian() * r * 0.6).normalize();
    }

    /** Une balle : elle va droit, s'arrête au premier bloc, blesse la première créature sur son chemin. */
    static void balle(ServerLevel level, ServerPlayer tireur, Vec3 oeil, Vec3 canon, Vec3 dir, Spec spec) {
        Vec3 fin = oeil.add(dir.scale(spec.portee()));
        BlockHitResult mur = level.clip(new ClipContext(oeil, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, tireur));
        if (mur.getType() != HitResult.Type.MISS) {
            fin = mur.getLocation();
        }
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(level, tireur, oeil, fin, new AABB(oeil, fin).inflate(1),
                e -> e instanceof LivingEntity && e != tireur && e.isAlive() && !e.isSpectator(), 0F); // 0 : la vraie silhouette, sans marge qui attire les balles
        boolean chair = false;
        if (touche != null) {
            fin = touche.getLocation();
            LivingEntity cible = (LivingEntity) touche.getEntity();
            // les rafales passent : pas d'invincibilité entre deux balles
            cible.invulnerableTime = 0;
            DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BALLE), tireur);
            chair = cible.hurt(source, spec.degats());
            cible.knockback(0.12, -dir.x, -dir.z);
        }
        tracante(level, canon, fin, spec.degats() >= 8 ? 1 : 0, touche != null ? (chair ? 2 : 1) : mur.getType() != HitResult.Type.MISS ? 0 : -1);
    }

    /**
     * La traçante, la flamme du canon et l'impact, dessinés par chaque client proche (un seul paquet par balle).
     * impact : -1 rien, 0 un bloc (poussière), 1 une créature qui ne saigne pas, 2 une créature touchée.
     */
    public static void tracante(ServerLevel level, Vec3 de, Vec3 a, int calibre, int impact) {
        Cartel.Tracante p = new Cartel.Tracante(de, a, calibre, impact);
        for (ServerPlayer j : level.players()) {
            if (j.distanceToSqr(de) < 128 * 128) {
                PacketDistributor.sendToPlayer(j, p);
            }
        }
        if (impact == 0) {
            level.sendParticles(ParticleTypes.SMOKE, a.x, a.y, a.z, 2, 0.05, 0.05, 0.05, 0.01);
        }
    }

    // ------------------------------------------------------------------ l'info-bulle

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
        switch (spec.tir()) {
            case GRENADE -> lignes.add(Component.literal("Clic droit : la lancer. 3 secondes, puis boum.").withStyle(ChatFormatting.GRAY));
            case MOLOTOV -> lignes.add(Component.literal("Clic droit : le lancer. Tout brûle autour (aucun bloc ne casse).").withStyle(ChatFormatting.GRAY));
            default -> {
                lignes.add(Component.literal(spec.auto() ? "Clic droit maintenu : rafale" : "Clic droit : tirer").withStyle(ChatFormatting.GRAY));
                lignes.add(Component.literal("Munitions : " + munitions(stack) + " / " + spec.chargeur() + " (infinies, R pour recharger)").withStyle(ChatFormatting.GRAY));
                if (spec.tir() == Tir.BALLE) {
                    lignes.add(Component.literal("Dégâts : " + fmt(spec.degats() / 2F) + (spec.plombs() > 1 ? " cœur × " + spec.plombs() + " plombs" : " cœur(s) par balle"))
                            .withStyle(ChatFormatting.DARK_GRAY));
                } else {
                    lignes.add(Component.literal("Explose à l'impact, sans casser de blocs").withStyle(ChatFormatting.DARK_GRAY));
                }
                if (spec.lunette()) {
                    lignes.add(Component.literal("Accroupi : on vise dans la lunette").withStyle(ChatFormatting.DARK_GRAY));
                }
                if (deuxMains()) {
                    lignes.add(Component.literal("Se tient à deux mains").withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
    }

    private static String fmt(float v) {
        return v == Mth.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
