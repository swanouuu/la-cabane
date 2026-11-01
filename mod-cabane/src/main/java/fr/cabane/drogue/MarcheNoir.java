package fr.cabane.drogue;

import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneItems;
import fr.cabane.economie.Economie;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le marché noir de Beauvais : la weed et la coca qui poussent dans les champs, les labos clandestins (Labos.java),
 * les joints, et le Fourgue qui vend les graines et rachète la marchandise.
 * Chaque produit donne un coup de fouet bref… puis une descente longue et pénible. Et en reprendre pendant la descente,
 * c'est l'overdose. La drogue, c'est mal : le jeu le fait sentir.
 */
public final class MarcheNoir {
    public static final DeferredRegister<MobEffect> EFFETS = DeferredRegister.create(Registries.MOB_EFFECT, Cabane.MODID);
    /** Le joint : on plane, on rit, on a faim. */
    public static final DeferredHolder<MobEffect, Planant> PLANANT = EFFETS.register("planant", Planant::new);
    /** La montée d'une drogue (son niveau dit laquelle) : à la fin, la descente tombe. */
    public static final DeferredHolder<MobEffect, Defonce> DEFONCE = EFFETS.register("defonce", Defonce::new);
    /** La descente : vide, lent, nauséeux. */
    public static final DeferredHolder<MobEffect, Descente> DESCENTE = EFFETS.register("descente", Descente::new);

    // ---- les plantes : à semer sur de la terre labourée, comme le blé
    public static final DeferredBlock<Plante> PLANT_WEED = CabaneBlocks.BLOCKS.registerBlock("plant_weed", p -> new Plante(p, () -> MarcheNoir.GRAINE_WEED.get()),
            BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT));
    public static final DeferredBlock<Plante> PLANT_COCA = CabaneBlocks.BLOCKS.registerBlock("plant_coca", p -> new Plante(p, () -> MarcheNoir.GRAINE_COCA.get()),
            BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT));
    public static final DeferredItem<ItemNameBlockItem> GRAINE_WEED = CabaneItems.ITEMS.register("graine_weed",
            () -> new ItemNameBlockItem(PLANT_WEED.get(), new Item.Properties()));
    public static final DeferredItem<ItemNameBlockItem> GRAINE_COCA = CabaneItems.ITEMS.register("graine_coca",
            () -> new ItemNameBlockItem(PLANT_COCA.get(), new Item.Properties()));
    public static final DeferredItem<Item> TETE_WEED = CabaneItems.ITEMS.register("tete_weed", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> FEUILLE_COCA = CabaneItems.ITEMS.register("feuille_coca", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> HASH = CabaneItems.ITEMS.register("hash", () -> new Item(new Item.Properties()));

    // ---- ce qui se consomme
    public static final DeferredItem<Drogue> JOINT = drogue("joint", Produit.JOINT);
    public static final DeferredItem<Drogue> JOINT_HASH = drogue("joint_hash", Produit.JOINT_HASH);
    public static final DeferredItem<Drogue> COCAINE = drogue("cocaine", Produit.COCAINE);
    public static final DeferredItem<Drogue> METH = drogue("meth", Produit.METH);

    /** Ce que le Fourgue rachète, et combien. */
    private static final Map<Supplier<? extends Item>, Long> RACHAT = Map.of(
            TETE_WEED, 12L, Labos.WEED_SECHEE, 30L, HASH, 90L, JOINT, 45L, JOINT_HASH, 140L,
            Labos.PATE_COCA, 120L, COCAINE, 160L, Labos.PLAQUE_METH, 300L, METH, 120L);

    private static DeferredItem<Drogue> drogue(String nom, Produit produit) {
        return CabaneItems.ITEMS.register(nom, () -> new Drogue(new Item.Properties().stacksTo(16), produit));
    }

    public static void register(IEventBus modBus) {
        EFFETS.register(modBus);
        Labos.init();
        NeoForge.EVENT_BUS.addListener(MarcheNoir::finDeMontee);
    }

    // ================================================================ les produits : la montée, puis la descente

    public enum Produit {
        JOINT("Un joint d'herbe du jardin", 20 * 90, 20 * 60, 0xFF6FB04A,
                List.of(() -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 90, 0), () -> new MobEffectInstance(MobEffects.REGENERATION, 20 * 10, 0)),
                List.of(() -> new MobEffectInstance(MobEffects.HUNGER, 20 * 60, 1), () -> new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 0)),
                "La fringale… et la flemme."),
        JOINT_HASH("Un joint de hash, beaucoup plus chargé", 20 * 150, 20 * 120, 0xFF8A5A2B,
                List.of(() -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 150, 0), () -> new MobEffectInstance(MobEffects.REGENERATION, 20 * 15, 0),
                        () -> new MobEffectInstance(MobEffects.CONFUSION, 20 * 8, 0)),
                List.of(() -> new MobEffectInstance(MobEffects.HUNGER, 20 * 120, 1), () -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 120, 1),
                        () -> new MobEffectInstance(MobEffects.WEAKNESS, 20 * 120, 0)),
                "T'es complètement à l'ouest. Tu bouges plus."),
        COCAINE("Rapide, tout voir… pendant une minute", 20 * 60, 20 * 300, 0xFFF0F0F0,
                List.of(() -> new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60, 2), () -> new MobEffectInstance(MobEffects.DIG_SPEED, 20 * 60, 1),
                        () -> new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60, 0)),
                List.of(() -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 300, 1), () -> new MobEffectInstance(MobEffects.WEAKNESS, 20 * 300, 1),
                        () -> new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 300, 0), () -> new MobEffectInstance(MobEffects.HUNGER, 20 * 300, 0)),
                "La descente. Cinq minutes à ramper. Ça valait le coup ?"),
        METH("Invincible pendant 45 secondes. Et après…", 20 * 45, 20 * 300, 0xFF5EC8F0,
                List.of(() -> new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 45, 1), () -> new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 45, 0),
                        () -> new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 45, 0), () -> new MobEffectInstance(MobEffects.JUMP, 20 * 45, 1)),
                List.of(() -> new MobEffectInstance(MobEffects.WEAKNESS, 20 * 300, 1), () -> new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 300, 0),
                        () -> new MobEffectInstance(MobEffects.HUNGER, 20 * 300, 1), () -> new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 300, 0),
                        () -> new MobEffectInstance(MobEffects.POISON, 20 * 15, 0)),
                "Tes dents… elles bougent ? Cinq minutes de calvaire.");

        final String description;
        final int montee;
        final int descente;
        final int couleur;
        final List<Supplier<MobEffectInstance>> effetsMontee;
        final List<Supplier<MobEffectInstance>> effetsDescente;
        final String messageDescente;

        Produit(String description, int montee, int descente, int couleur, List<Supplier<MobEffectInstance>> effetsMontee,
                List<Supplier<MobEffectInstance>> effetsDescente, String messageDescente) {
            this.description = description;
            this.montee = montee;
            this.descente = descente;
            this.couleur = couleur;
            this.effetsMontee = effetsMontee;
            this.effetsDescente = effetsDescente;
            this.messageDescente = messageDescente;
        }

        boolean fume() {
            return this == JOINT || this == JOINT_HASH;
        }
    }

    public static class Drogue extends Item {
        private final Produit produit;

        Drogue(Properties properties, Produit produit) {
            super(properties);
            this.produit = produit;
        }

        @Override
        public UseAnim getUseAnimation(ItemStack stack) {
            return UseAnim.TOOT_HORN;
        }

        @Override
        public int getUseDuration(ItemStack stack, LivingEntity entity) {
            return produit.fume() ? 40 : 24;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            joueur.startUsingItem(main);
            return InteractionResultHolder.consume(joueur.getItemInHand(main));
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity e) {
            if (e instanceof ServerPlayer joueur) {
                consommer(joueur, produit);
            }
            stack.consume(1, e);
            return stack;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(produit.description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("Montée " + produit.montee / 20 + " s, puis descente " + produit.descente / 20 + " s").withStyle(ChatFormatting.RED));
            tooltip.add(Component.literal("En reprendre avant la fin : overdose.").withStyle(ChatFormatting.DARK_RED));
        }
    }

    /** Prendre un produit : la montée commence… sauf si on est encore dedans, et là c'est l'overdose. */
    static void consommer(ServerPlayer joueur, Produit produit) {
        ServerLevel level = joueur.serverLevel();
        if (produit.fume()) {
            fr.cabane.fumee.Fumee.souffler(joueur, new org.joml.Vector3f(0.75F, 0.78F, 0.72F), 24);
        } else {
            level.playSound(null, joueur.blockPosition(), SoundEvents.FOX_SNIFF, SoundSource.PLAYERS, 1F, 0.8F);
        }
        if (joueur.hasEffect(DEFONCE) || joueur.hasEffect(DESCENTE)) {
            overdose(joueur);
            return;
        }
        for (Supplier<MobEffectInstance> effet : produit.effetsMontee) {
            joueur.addEffect(effet.get());
        }
        if (produit.fume()) {
            joueur.addEffect(new MobEffectInstance(PLANANT, produit.montee, produit == Produit.JOINT_HASH ? 1 : 0));
        }
        joueur.addEffect(new MobEffectInstance(DEFONCE, produit.montee, produit.ordinal(), false, false, true));
    }

    private static void overdose(ServerPlayer joueur) {
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 10, 0));
        joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 20, 0));
        joueur.addEffect(new MobEffectInstance(MobEffects.WITHER, 20 * 6, 1));
        joueur.hurt(joueur.damageSources().magic(), 8F);
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.5F, 1.4F);
        joueur.displayClientMessage(Component.literal("💀 OVERDOSE. Ton cœur lâche presque.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
    }

    /** Quand la montée finit d'elle-même, la descente tombe (le lait, lui, coupe tout : c'est le seul remède). */
    private static void finDeMontee(MobEffectEvent.Expired event) {
        MobEffectInstance fin = event.getEffectInstance();
        // (l'événement peut arriver deux fois : une seule descente)
        if (fin == null || !fin.is(DEFONCE) || !(event.getEntity() instanceof ServerPlayer joueur) || joueur.hasEffect(DESCENTE)) {
            return;
        }
        Produit produit = Produit.values()[Math.min(fin.getAmplifier(), Produit.values().length - 1)];
        for (Supplier<MobEffectInstance> effet : produit.effetsDescente) {
            joueur.addEffect(effet.get());
        }
        joueur.addEffect(new MobEffectInstance(DESCENTE, produit.descente, produit.ordinal(), false, false, true));
        joueur.displayClientMessage(Component.literal("😵 " + produit.messageDescente).withStyle(ChatFormatting.DARK_PURPLE), false);
        joueur.displayClientMessage(Component.literal("La drogue, c'est mal.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
    }

    // ================================================================ les effets

    public static class Planant extends MobEffect {
        private static final String[] PENSEES = {"hihihi", "t'as vu les nuages ?", "j'ai trop faim là", "attends… on est où ?",
                "c'est trop beau un bloc de terre en vrai", "frère… et si les creepers étaient juste tristes ?", "je kiffe ce moment"};

        Planant() {
            super(MobEffectCategory.NEUTRAL, 0x6FB04A);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duree, int niveau) {
            return duree % 20 == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity e, int niveau) {
            if (e instanceof ServerPlayer joueur) {
                joueur.causeFoodExhaustion(0.5F + niveau * 0.5F);
                RandomSource r = joueur.getRandom();
                if (r.nextInt(6) == 0) {
                    joueur.displayClientMessage(Component.literal(PENSEES[r.nextInt(PENSEES.length)]).withStyle(ChatFormatting.GREEN), true);
                }
            }
            return true;
        }
    }

    public static class Defonce extends MobEffect {
        Defonce() {
            super(MobEffectCategory.NEUTRAL, 0xF0F0F0);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duree, int niveau) {
            return duree % 20 == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity e, int niveau) {
            if (!(e instanceof ServerPlayer joueur)) {
                return true;
            }
            // la cocaïne : on « voit » tout ce qui bouge autour, à travers les murs
            if (niveau == Produit.COCAINE.ordinal()) {
                for (LivingEntity autre : joueur.level().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(32), x -> x != joueur)) {
                    autre.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
                }
            }
            // le cœur qui cogne
            if (niveau >= Produit.COCAINE.ordinal() && joueur.getRandom().nextInt(3) == 0) {
                joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.6F, 1.6F);
            }
            return true;
        }
    }

    public static class Descente extends MobEffect {
        private static final String[] PENSEES = {"tu te sens vide…", "pourquoi t'as fait ça…", "t'as froid. Très froid.", "tout est gris.",
                "t'en voudrais encore… non. NON."};

        Descente() {
            super(MobEffectCategory.HARMFUL, 0x4A3A5A);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duree, int niveau) {
            return duree % 20 == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity e, int niveau) {
            if (!(e instanceof ServerPlayer joueur)) {
                return true;
            }
            RandomSource r = joueur.getRandom();
            if (niveau >= Produit.COCAINE.ordinal() && r.nextInt(12) == 0 && !joueur.hasEffect(MobEffects.CONFUSION)) {
                joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 5, 0, false, false, false));
            }
            if (r.nextInt(15) == 0) {
                joueur.displayClientMessage(Component.literal(PENSEES[r.nextInt(PENSEES.length)]).withStyle(ChatFormatting.DARK_PURPLE), true);
            }
            return true;
        }
    }

    // ================================================================ les plantes

    public static class Plante extends CropBlock {
        private final Supplier<? extends ItemLike> graine;

        Plante(Properties properties, Supplier<? extends ItemLike> graine) {
            super(properties);
            this.graine = graine;
        }

        @Override
        protected ItemLike getBaseSeedId() {
            return graine.get();
        }
    }

    // ================================================================ le Fourgue

    /** Le marché noir ne s'ouvre qu'à côté du Fourgue (un PNJ portant le tag « fourgue »). */
    public static boolean presDuFourgue(ServerPlayer joueur) {
        return !joueur.level().getEntities(joueur, joueur.getBoundingBox().inflate(8), e -> e.getTags().contains("fourgue")).isEmpty();
    }

    public static void ouvrir(ServerPlayer joueur) {
        if (!presDuFourgue(joueur)) {
            joueur.displayClientMessage(Component.literal("Personne ne vend ça ici…").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        fr.cabane.boutique.Boutique.ouvrir(joueur, true);
    }

    /** Le Fourgue rachète toute la marchandise du joueur, cash. */
    public static void vendre(ServerPlayer joueur) {
        if (!presDuFourgue(joueur)) {
            joueur.displayClientMessage(Component.literal("Personne n'achète ça ici…").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        long total = 0;
        var inventaire = joueur.getInventory();
        for (int i = 0; i < inventaire.getContainerSize(); i++) {
            ItemStack stack = inventaire.getItem(i);
            for (var entree : RACHAT.entrySet()) {
                if (!stack.isEmpty() && stack.is(entree.getKey().get())) {
                    total += entree.getValue() * stack.getCount();
                    inventaire.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        if (total == 0) {
            joueur.displayClientMessage(Component.literal("« T'as rien pour moi ? Reviens quand t'auras de la marchandise. »").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        Economie.crediter(joueur.server, joueur.getUUID(), total);
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 1F, 0.7F);
        joueur.displayClientMessage(Component.literal("« Ça marche. » Le Fourgue te glisse ").append(Economie.texte(total)).append(" en liquide.")
                .withStyle(ChatFormatting.DARK_GREEN), false);
    }

    private MarcheNoir() {
    }
}
