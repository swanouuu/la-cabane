package fr.cabane.boisson;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le bar de Beauvais : des bières (blonde, brune, la fameuse 8.6), du vin, du pastis, du rhum… et des sodas.
 * L'alcool rend « ivre » (un effet qui monte à chaque verre : hoquet, tête qui tourne, démarche titubante,
 * trou noir), les sodas donnent un coup de fouet et font un peu redescendre.
 */
public final class Boissons {
    public static final DeferredRegister<MobEffect> EFFETS = DeferredRegister.create(Registries.MOB_EFFECT, Cabane.MODID);
    public static final DeferredHolder<MobEffect, Ivresse> IVRESSE = EFFETS.register("ivresse", Ivresse::new);

    /** Ivresse maximale (niveau 7 : ivre mort). */
    private static final int MAX = 6;
    private static final String[] ETATS = {"Pompette", "Éméché", "Bourré", "Bien bourré", "Complètement bourré", "Ivre mort", "Ivre mort"};

    public static final DeferredItem<Boisson> BIERE_BLONDE = boisson("biere_blonde", new Profil(1, 2, 0.2F, true, "5 % · légère et bien fraîche"));
    public static final DeferredItem<Boisson> BIERE_BRUNE = boisson("biere_brune", new Profil(1, 3, 0.3F, true, "6,5 % · ronde et maltée"));
    public static final DeferredItem<Boisson> BIERE_86 = boisson("biere_86", new Profil(2, 2, 0.2F, true, "8,6 % · la légende des fins de soirée",
            () -> new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60, 0)));
    public static final DeferredItem<Boisson> VIN_ROUGE = boisson("vin_rouge", new Profil(2, 2, 0.4F, true, "13 % · un petit rouge de Beauvais",
            () -> new MobEffectInstance(MobEffects.REGENERATION, 20 * 8, 0)));
    public static final DeferredItem<Boisson> PASTIS = boisson("pastis", new Profil(2, 1, 0.1F, true, "45 % · un jaune, bien frais",
            () -> new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60, 0)));
    public static final DeferredItem<Boisson> RHUM = boisson("rhum", new Profil(3, 1, 0.1F, true, "40 % · le trésor des pirates",
            () -> new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 30, 1)));
    public static final DeferredItem<Boisson> COCA = boisson("coca", new Profil(-1, 2, 0.3F, false, "Plein de sucre : ça réveille",
            () -> new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 45, 0)));
    public static final DeferredItem<Boisson> FANTA = boisson("fanta", new Profil(-1, 2, 0.3F, false, "Goût orange, ça pétille",
            () -> new MobEffectInstance(MobEffects.JUMP, 20 * 45, 0)));
    public static final DeferredItem<Boisson> ICE_TEA = boisson("ice_tea", new Profil(-1, 3, 0.4F, false, "À la pêche, la boisson du goûter",
            () -> new MobEffectInstance(MobEffects.REGENERATION, 20 * 5, 0)));

    public static final List<DeferredItem<Boisson>> TOUTES = List.of(BIERE_BLONDE, BIERE_BRUNE, BIERE_86, VIN_ROUGE, PASTIS, RHUM, COCA, FANTA, ICE_TEA);

    private static DeferredItem<Boisson> boisson(String nom, Profil profil) {
        return CabaneItems.ITEMS.register(nom, () -> new Boisson(new Item.Properties().stacksTo(16), profil));
    }

    /** Ce que fait une boisson : alcool &gt; 0 fait monter l'ivresse d'autant, &lt; 0 la fait redescendre (les sodas). */
    private record Profil(int alcool, int nourriture, float saturation, boolean bouteille, String description, List<Supplier<MobEffectInstance>> bonus) {
        @SafeVarargs
        Profil(int alcool, int nourriture, float saturation, boolean bouteille, String description, Supplier<MobEffectInstance>... bonus) {
            this(alcool, nourriture, saturation, bouteille, description, List.of(bonus));
        }
    }

    public static void register(IEventBus modBus) {
        EFFETS.register(modBus);
    }

    public static class Boisson extends Item {
        private final int alcool;
        private final int nourriture;
        private final float saturation;
        private final boolean bouteille;
        private final String description;
        private final List<Supplier<MobEffectInstance>> bonus;

        Boisson(Properties properties, Profil profil) {
            super(properties);
            this.alcool = profil.alcool();
            this.nourriture = profil.nourriture();
            this.saturation = profil.saturation();
            this.bouteille = profil.bouteille();
            this.description = profil.description();
            this.bonus = profil.bonus();
        }

        @Override
        public UseAnim getUseAnimation(ItemStack stack) {
            return UseAnim.DRINK;
        }

        @Override
        public int getUseDuration(ItemStack stack, LivingEntity entity) {
            return 32;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            return ItemUtils.startUsingInstantly(level, joueur, main);
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity buveur) {
            if (!level.isClientSide) {
                if (buveur instanceof Player joueur) {
                    joueur.getFoodData().eat(nourriture, saturation);
                }
                for (Supplier<MobEffectInstance> b : bonus) {
                    buveur.addEffect(b.get());
                }
                if (alcool > 0) {
                    boire(buveur, alcool);
                } else if (alcool < 0) {
                    degriser(buveur);
                }
            }
            boolean createur = buveur instanceof Player j && j.getAbilities().instabuild;
            stack.consume(1, buveur);
            if (!bouteille || createur) {
                return stack;
            }
            if (stack.isEmpty()) {
                return new ItemStack(Items.GLASS_BOTTLE);
            }
            if (buveur instanceof Player joueur && !joueur.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
                joueur.drop(new ItemStack(Items.GLASS_BOTTLE), false);
            }
            return stack;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            if (alcool > 0) {
                tooltip.add(Component.literal("Ivresse +" + alcool).withStyle(ChatFormatting.GOLD));
            } else if (alcool < 0) {
                tooltip.add(Component.literal("Fait un peu redescendre").withStyle(ChatFormatting.AQUA));
            }
            tooltip.add(Component.literal("Avec modération… ou pas.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Chaque verre fait monter l'ivresse et repousse le moment où ça passe. */
    public static void boire(LivingEntity buveur, int verres) {
        MobEffectInstance avant = buveur.getEffect(IVRESSE);
        int niveau = Math.min(MAX, avant == null ? verres - 1 : avant.getAmplifier() + verres);
        buveur.addEffect(new MobEffectInstance(IVRESSE, 20 * (90 + 45 * niveau), niveau, false, false, true));
        if (buveur instanceof ServerPlayer joueur) {
            joueur.displayClientMessage(Component.literal("🍺 " + ETATS[niveau] + " !").withStyle(niveau >= 4 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
        }
    }

    /** Un soda : on redescend d'un cran. */
    private static void degriser(LivingEntity buveur) {
        MobEffectInstance avant = buveur.getEffect(IVRESSE);
        if (avant == null) {
            return;
        }
        buveur.removeEffect(IVRESSE);
        if (avant.getAmplifier() > 0) {
            buveur.addEffect(new MobEffectInstance(IVRESSE, avant.getDuration(), avant.getAmplifier() - 1, false, false, true));
        }
        if (buveur instanceof ServerPlayer joueur) {
            joueur.displayClientMessage(Component.literal("Ça va un peu mieux…").withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** L'ivresse : le hoquet, puis la tête qui tourne, la démarche qui titube, et le trou noir. */
    public static class Ivresse extends MobEffect {
        Ivresse() {
            super(MobEffectCategory.HARMFUL, 0xE8A33A);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duree, int niveau) {
            return duree % 20 == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity e, int niveau) {
            if (e.level().isClientSide) {
                return true;
            }
            var rnd = e.getRandom();
            // le hoquet
            if (rnd.nextInt(5) == 0) {
                e.level().playSound(null, e.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.6F, 1.4F + rnd.nextFloat() * 0.4F);
                if (e instanceof ServerPlayer joueur) {
                    joueur.displayClientMessage(Component.literal(rnd.nextInt(6) == 0 ? "*hic* … je t'aime toi tu sais" : "*hic*").withStyle(ChatFormatting.GOLD), true);
                }
            }
            // la tête qui tourne
            if (niveau >= 1 && !e.hasEffect(MobEffects.CONFUSION) && rnd.nextInt(Math.max(2, 8 - niveau)) == 0) {
                e.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * (4 + niveau * 2), 0, false, false, false));
            }
            // les jambes qui flanchent, et on titube
            if (niveau >= 2) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, niveau >= 4 ? 1 : 0, false, false, false));
                if (e.onGround() && rnd.nextInt(3) == 0) {
                    e.push((rnd.nextDouble() - 0.5) * 0.25 * niveau, 0, (rnd.nextDouble() - 0.5) * 0.25 * niveau);
                    e.hurtMarked = true;
                }
            }
            // le trou noir
            if (niveau >= 4 && rnd.nextInt(niveau >= MAX ? 4 : 10) == 0 && !e.hasEffect(MobEffects.BLINDNESS)) {
                e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 3, 0, false, false, false));
                if (e instanceof ServerPlayer joueur) {
                    joueur.displayClientMessage(Component.literal("… trou noir …").withStyle(ChatFormatting.DARK_GRAY), true);
                }
            }
            return true;
        }
    }

    /** Pour le client : de combien la caméra tangue (0 si on est sobre). */
    public static int niveau(Player joueur) {
        Holder<MobEffect> ivresse = IVRESSE;
        MobEffectInstance i = joueur.getEffect(ivresse);
        return i == null ? -1 : i.getAmplifier();
    }

    private Boissons() {
    }
}
