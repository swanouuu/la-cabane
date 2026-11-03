package fr.cabane.pouvoir;

import com.mojang.serialization.MapCodec;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientFruit;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.joml.Vector3f;

/**
 * Les fruits du démon, d'après les maquettes de docs/fruits : le Magu Magu no Mi (celui d'Akainu, liste blanche
 * « lave ») et le Hie Hie no Mi (celui d'Aokiji, « glace »). On ne les trouve nulle part (ni coffre, ni recette, ni
 * marchand) : seul l'hôte les donne. Shift + clic droit sur un bloc le pose, un fruit en 3D pour le montrer ; clic droit
 * dessus le reprend ; clic droit maintenu le mange : le pouvoir monte en lui pendant 3 s, et il est mis tout seul sur
 * la liste blanche du fruit. Ensuite, toujours : l'eau lui fait perdre un demi-cœur par seconde, comme du poison ; et
 * le feu et la lave ne font rien à celui de la lave. Éveillé (touche Éveil, 2 minutes puis 1 minute de repos comme les
 * autres), sa main gauche devient de la lave (ou la droite, de la glace ; voir {@link ClientFruit}) :
 * <ul>
 * <li>celui de la glace marche sur l'eau : elle gèle sous ses pas, et refond 5 s après (endormi, il coule comme
 * l'autre) ;</li>
 * <li>clic gauche : toute arme frappe 1 cœur de plus, et enflamme 4 s (ou ralentit 3 s) ;</li>
 * <li>clic droit : un jet de lave (ou de glace) sur 12 blocs, 3 cœurs, la cible brûle 5 s (ou est très lente 4 s) ;</li>
 * <li>shift : l'éruption, 5 blocs autour, 4 cœurs, on vole en l'air et on brûle ; ou l'ère glaciaire, 3 cœurs, et on
 * reste gelé sur place 2 s.</li>
 * </ul>
 * Encore la touche Éveil : le niveau 2 (docs/fruits/planche-niveau2.png). La moitié de son corps devient de la lave
 * (ou de la glace), armure comprise ; le clic gauche frappe 2 cœurs de plus et éclabousse (ou le givre s'étend) à 2
 * blocs ; le clic droit lance un grand poing de magma (ou cinq lances de glace en éventail) ; le shift fait pleuvoir des
 * météores là où il vise (ou lance un faisan de glace géant) ; qui le frappe prend feu (ou gèle). Ces tirs volent pour
 * de vrai, tick après tick ({@link Tir}).
 * <p>
 * Rien ne reste au sol : les jets, les geysers et les pics de glace ne sont que dessinés.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Fruit {
    public static final String LAVE = "lave";
    public static final String GLACE = "glace";

    public static final ResourceLocation MAGU = Cabane.id("magu_magu_no_mi");
    public static final ResourceLocation HIE = Cabane.id("hie_hie_no_mi");

    public static final int RECHARGE_JET = 6 * 20;
    public static final int RECHARGE_CHOC = 20 * 20;
    private static final double PORTEE_JET = 12;
    private static final float DEGATS_JET = 6;
    private static final double RAYON_CHOC = 5;
    private static final float DEGATS_ERUPTION = 8;
    private static final float DEGATS_GLACIAIRE = 6;
    public static final int DUREE_GEL = 40;
    private static final float BONUS_ARME = 2;
    /** Le pouvoir monte en lui pendant 3 s quand il mange le fruit. */
    public static final int DUREE_REPAS = 60;

    /** L'éveil 2 : le grand poing (ou les lances) et la pluie de météores (ou le faisan), et ce qu'ils font. */
    public static final int RECHARGE_JET_2 = 8 * 20;
    public static final int RECHARGE_CHOC_2 = 25 * 20;
    private static final float BONUS_ARME_2 = 4;
    private static final float ECLABOUSSURE = 3;
    private static final float DEGATS_POING = 12;
    private static final float DEGATS_EXPLOSION = 4;
    private static final float DEGATS_LANCE = 6;
    private static final float DEGATS_METEORE = 5;
    private static final float DEGATS_FAISAN = 12;
    private static final double PORTEE_2 = 24;
    private static final double PORTEE_PLUIE = 30;
    public static final int GEL_FAISAN = 60;
    /** Les sortes de tirs de l'éveil 2 : le grand poing, une lance, une météore, le faisan. */
    public static final int POING = 0;
    public static final int LANCE = 1;
    public static final int METEORE = 2;
    public static final int FAISAN = 3;
    /** La sorte d'onde de la pluie de météores (la zone et le nuage, côté client). */
    public static final String PLUIE = "pluie";

    private static final ResourceKey<DamageType> MAGMA = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("magma"));
    private static final ResourceKey<DamageType> GIVRE = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("givre"));
    private static final ResourceKey<DamageType> EAU = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("eau"));

    public static boolean est(String pouvoir) {
        return LAVE.equals(pouvoir) || GLACE.equals(pouvoir);
    }

    /** Qui a son fruit éveillé en ce moment (et à quel niveau), et quand son jet et son shift seront prêts (ticks du serveur). */
    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Map<UUID, Integer> PRET_JET = new HashMap<>();
    private static final Map<UUID, Integer> PRET_CHOC = new HashMap<>();

    /** Ce que l'ère glaciaire a gelé sur place : l'entité, où elle reste, et jusqu'à quand. */
    private record Gele(ServerLevel monde, Vec3 pos, int fin) {
    }

    private static final Map<Integer, Gele> GELES = new HashMap<>();

    /** La glace qu'il fait sous ses pas : où elle est, et quand elle refond (même la nuit, où la glace givrée tient). */
    private record Glace(ServerLevel monde, BlockPos pos) {
    }

    private static final Map<Glace, Integer> GLACES = new HashMap<>();
    private static final int FONTE = 5 * 20;
    /** Le temps de nos propres coups : la frappe de l'arme ne doit pas les compter deux fois. */
    private static boolean dansPouvoir;

    // ================================================================ les fruits : l'objet, et le fruit posé

    @SubscribeEvent
    public static void enregistrer(RegisterEvent event) {
        event.register(Registries.BLOCK, MAGU, () -> new FruitPose(proprietes(true), LAVE));
        event.register(Registries.BLOCK, HIE, () -> new FruitPose(proprietes(false), GLACE));
        event.register(Registries.ITEM, MAGU, () -> new FruitItem(BuiltInRegistries.BLOCK.get(MAGU), LAVE));
        event.register(Registries.ITEM, HIE, () -> new FruitItem(BuiltInRegistries.BLOCK.get(HIE), GLACE));
    }

    private static BlockBehaviour.Properties proprietes(boolean lave) {
        return BlockBehaviour.Properties.of().mapColor(lave ? MapColor.COLOR_ORANGE : MapColor.ICE).strength(0.3F).sound(SoundType.WART_BLOCK)
                .noOcclusion().pushReaction(PushReaction.DESTROY).lightLevel(s -> lave ? 6 : 0);
    }

    /** Dans l'onglet du mod, pour l'hôte en créatif (c'est le seul endroit où on les trouve). */
    @SubscribeEvent
    public static void onglet(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().location().equals(Cabane.id("cabane"))) {
            event.accept(BuiltInRegistries.ITEM.get(HIE));
            event.accept(BuiltInRegistries.ITEM.get(MAGU));
        }
    }

    /** Le fruit en main : il se mange (clic droit maintenu) ; accroupi, il se pose sur le bloc visé. */
    public static final class FruitItem extends BlockItem {
        private final String sorte;

        FruitItem(Block bloc, String sorte) {
            super(bloc, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()
                    .food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.6F).alwaysEdible().build()));
            this.sorte = sorte;
        }

        @Override
        public InteractionResult useOn(UseOnContext contexte) {
            // sans s'accroupir, on ne le pose pas : on le mange (le clic passe à use, comme une pomme)
            if (contexte.getPlayer() != null && !contexte.getPlayer().isSecondaryUseActive()) {
                return InteractionResult.PASS;
            }
            return super.useOn(contexte);
        }

        @Override
        public ItemStack finishUsingItem(ItemStack objet, Level monde, LivingEntity mangeur) {
            ItemStack reste = super.finishUsingItem(objet, monde, mangeur);
            if (mangeur instanceof ServerPlayer joueur) {
                manger(joueur, sorte);
            }
            return reste;
        }

        @Override
        public Component getName(ItemStack objet) {
            return Component.translatable(getDescriptionId(objet)).withStyle(GLACE.equals(sorte) ? ChatFormatting.AQUA : ChatFormatting.GOLD);
        }

        @Override
        public void appendHoverText(ItemStack objet, Item.TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.literal(GLACE.equals(sorte) ? "Fruit du démon : la glace" : "Fruit du démon : la lave").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal("Clic droit maintenu : le manger").withStyle(ChatFormatting.DARK_GRAY));
            lignes.add(Component.literal("Shift + clic droit : le poser").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Le fruit posé (sur une table, pour le montrer) : clic droit dessus, on le reprend. */
    public static final class FruitPose extends HorizontalDirectionalBlock {
        private static final VoxelShape FORME = Block.box(3, 0, 3, 13, 13, 13);
        private final String sorte;

        FruitPose(BlockBehaviour.Properties proprietes, String sorte) {
            super(proprietes);
            this.sorte = sorte;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return simpleCodec(p -> new FruitPose(p, sorte));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext contexte) {
            return defaultBlockState().setValue(FACING, contexte.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState etat, BlockGetter monde, BlockPos pos, CollisionContext contexte) {
            return FORME;
        }

        @Override
        protected boolean canSurvive(BlockState etat, LevelReader monde, BlockPos pos) {
            return Block.canSupportCenter(monde, pos.below(), Direction.UP);
        }

        @Override
        protected BlockState updateShape(BlockState etat, Direction cote, BlockState voisin, LevelAccessor monde, BlockPos pos, BlockPos posVoisin) {
            return cote == Direction.DOWN && !canSurvive(etat, monde, pos) ? Blocks.AIR.defaultBlockState()
                    : super.updateShape(etat, cote, voisin, monde, pos, posVoisin);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState etat, Level monde, BlockPos pos, Player joueur, BlockHitResult visee) {
            if (!monde.isClientSide) {
                monde.removeBlock(pos, false);
                ItemStack fruit = new ItemStack(this);
                if (!joueur.getInventory().add(fruit)) {
                    joueur.drop(fruit, false);
                }
                monde.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 0.9F);
            }
            return InteractionResult.sidedSuccess(monde.isClientSide);
        }

        /** Posé, il vit un peu : un flocon de temps en temps, ou une petite flamme et de la fumée. */
        @Override
        public void animateTick(BlockState etat, Level monde, BlockPos pos, RandomSource hasard) {
            double x = pos.getX() + 0.5 + (hasard.nextDouble() - 0.5) * 0.6, y = pos.getY() + 0.2 + hasard.nextDouble() * 0.6;
            double z = pos.getZ() + 0.5 + (hasard.nextDouble() - 0.5) * 0.6;
            if (GLACE.equals(sorte)) {
                if (hasard.nextInt(3) == 0) {
                    monde.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0, 0.01, 0);
                }
            } else {
                if (hasard.nextInt(3) == 0) {
                    monde.addParticle(ParticleTypes.SMALL_FLAME, x, pos.getY() + 0.75, z, 0, 0.01, 0);
                }
                if (hasard.nextInt(6) == 0) {
                    monde.addParticle(ParticleTypes.SMOKE, x, pos.getY() + 0.85, z, 0, 0.02, 0);
                }
            }
        }
    }

    // ================================================================ réseau

    /** Client → serveur : clic droit, fruit éveillé (le jet). */
    public record Jet() implements CustomPacketPayload {
        public static final Type<Jet> TYPE = new Type<>(Cabane.id("fruit_jet"));
        public static final StreamCodec<ByteBuf, Jet> CODEC = StreamCodec.unit(new Jet());

        @Override
        public Type<Jet> type() {
            return TYPE;
        }
    }

    /** Client → serveur : on vient de s'accroupir, fruit éveillé (l'éruption ou l'ère glaciaire). */
    public record Choc() implements CustomPacketPayload {
        public static final Type<Choc> TYPE = new Type<>(Cabane.id("fruit_choc"));
        public static final StreamCodec<ByteBuf, Choc> CODEC = StreamCodec.unit(new Choc());

        @Override
        public Type<Choc> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui le voient : ce joueur mange ce fruit (l'animation de 3 s). */
    public record Repas(UUID joueur, String sorte) implements CustomPacketPayload {
        public static final Type<Repas> TYPE = new Type<>(Cabane.id("fruit_repas"));
        public static final StreamCodec<ByteBuf, Repas> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Repas::joueur, ByteBufCodecs.STRING_UTF8, Repas::sorte, Repas::new);

        @Override
        public Type<Repas> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un jet de lave (ou de glace), de « de » à « a ». */
    public record Trait(UUID joueur, String sorte, Vector3f de, Vector3f a) implements CustomPacketPayload {
        public static final Type<Trait> TYPE = new Type<>(Cabane.id("fruit_trait"));
        public static final StreamCodec<ByteBuf, Trait> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Trait::joueur, ByteBufCodecs.STRING_UTF8, Trait::sorte, ByteBufCodecs.VECTOR3F, Trait::de,
                ByteBufCodecs.VECTOR3F, Trait::a, Trait::new);

        @Override
        public Type<Trait> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : une éruption (ou une ère glaciaire) autour de ce point. */
    public record Onde(UUID joueur, String sorte, Vector3f centre) implements CustomPacketPayload {
        public static final Type<Onde> TYPE = new Type<>(Cabane.id("fruit_onde"));
        public static final StreamCodec<ByteBuf, Onde> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Onde::joueur, ByteBufCodecs.STRING_UTF8, Onde::sorte, ByteBufCodecs.VECTOR3F, Onde::centre, Onde::new);

        @Override
        public Type<Onde> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : cette entité est prise dans un bloc de glace pendant {@code ticks}. */
    public record Gel(int entite, int ticks) implements CustomPacketPayload {
        public static final Type<Gel> TYPE = new Type<>(Cabane.id("fruit_gel"));
        public static final StreamCodec<ByteBuf, Gel> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Gel::entite, ByteBufCodecs.VAR_INT, Gel::ticks, Gel::new);

        @Override
        public Type<Gel> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un tir de l'éveil 2 part (sa sorte, d'où, vers où, à quelle vitesse, jusqu'où). */
    public record Vol(int id, int sorte, Vector3f de, Vector3f dir, float vitesse, float portee) implements CustomPacketPayload {
        public static final Type<Vol> TYPE = new Type<>(Cabane.id("fruit_vol"));
        public static final StreamCodec<ByteBuf, Vol> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Vol::id, ByteBufCodecs.VAR_INT, Vol::sorte, ByteBufCodecs.VECTOR3F, Vol::de, ByteBufCodecs.VECTOR3F, Vol::dir,
                ByteBufCodecs.FLOAT, Vol::vitesse, ByteBufCodecs.FLOAT, Vol::portee, Vol::new);

        @Override
        public Type<Vol> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : ce tir s'arrête là ({@code eclate} : il explose ou s'écrase). */
    public record Arret(int id, Vector3f pos, boolean eclate) implements CustomPacketPayload {
        public static final Type<Arret> TYPE = new Type<>(Cabane.id("fruit_arret"));
        public static final StreamCodec<ByteBuf, Arret> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Arret::id, ByteBufCodecs.VECTOR3F, Arret::pos, ByteBufCodecs.BOOL, Arret::eclate, Arret::new);

        @Override
        public Type<Arret> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Jet.TYPE, Jet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        jet(joueur);
                    }
                }))
                .playToServer(Choc.TYPE, Choc.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        choc(joueur);
                    }
                }))
                .playToClient(Repas.TYPE, Repas.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.repas(p)))
                .playToClient(Trait.TYPE, Trait.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.trait(p)))
                .playToClient(Onde.TYPE, Onde.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.onde(p)))
                .playToClient(Gel.TYPE, Gel.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.gel(p)))
                .playToClient(Vol.TYPE, Vol.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.vol(p)))
                .playToClient(Arret.TYPE, Arret.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit.arret(p)));
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

    // ================================================================ le manger

    /** Il mange le fruit : le voilà sur la liste blanche, et pendant 3 s il s'élève un peu, figé, le pouvoir monte. */
    static void manger(ServerPlayer joueur, String sorte) {
        boolean glace = GLACE.equals(sorte);
        Pouvoirs.donner(joueur, sorte, glace ? Pouvoirs.Couleur.CYAN : Pouvoirs.Couleur.ORANGE);
        joueur.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 14, 0, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, DUREE_REPAS + 10, 0, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DUREE_REPAS, 4, false, false));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Repas(joueur.getUUID(), sorte));
        String nom = glace ? "Hie Hie no Mi" : "Magu Magu no Mi";
        ChatFormatting teinte = glace ? ChatFormatting.AQUA : ChatFormatting.GOLD;
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(5, 45, 15));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(glace ? "La glace entre en toi" : "La lave entre en toi")
                .withStyle(ChatFormatting.WHITE)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal(nom).withStyle(teinte, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Tu as mangé le " + nom + " : tu es sur la whitelist " + sorte + ".").withStyle(teinte));
        joueur.sendSystemMessage(Component.literal("Touche Éveil pour réveiller ton pouvoir. Attention : l'eau te fait mal, maintenant.")
                .withStyle(ChatFormatting.GRAY));
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.6F, 0.8F);
        monde.playSound(null, joueur.blockPosition(), glace ? SoundEvents.AMETHYST_BLOCK_RESONATE : SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1F, 0.6F);
    }

    // ================================================================ s'éveiller, s'endormir

    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        boolean glace = GLACE.equals(Pouvoirs.pouvoir(joueur));
        ServerLevel monde = joueur.serverLevel();
        Vec3 main = main(joueur, glace);
        if (niveau >= 2) {
            // la moitié du corps change d'un coup : une gerbe tout autour de lui
            monde.sendParticles(glace ? ParticleTypes.SNOWFLAKE : ParticleTypes.FLAME, joueur.getX(), joueur.getY(0.6), joueur.getZ(), 70, 0.5, 0.8, 0.5, 0.08);
            if (glace) {
                son(joueur, SoundEvents.GLASS_BREAK, 1.2F, 0.5F);
                son(joueur, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4F, 0.5F);
                son(joueur, SoundEvents.POWDER_SNOW_BREAK, 1.2F, 0.5F);
            } else {
                monde.sendParticles(ParticleTypes.LAVA, joueur.getX(), joueur.getY(0.6), joueur.getZ(), 16, 0.4, 0.6, 0.4, 0);
                son(joueur, SoundEvents.LAVA_EXTINGUISH, 1.2F, 0.5F);
                son(joueur, SoundEvents.BLAZE_SHOOT, 1F, 0.4F);
                son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 0.4F, 1.3F);
            }
            return;
        }
        if (glace) {
            monde.sendParticles(ParticleTypes.SNOWFLAKE, main.x, main.y, main.z, 24, 0.25, 0.25, 0.25, 0.04);
            son(joueur, SoundEvents.GLASS_BREAK, 0.8F, 1.5F);
            son(joueur, SoundEvents.POWDER_SNOW_PLACE, 1F, 0.7F);
        } else {
            monde.sendParticles(ParticleTypes.FLAME, main.x, main.y, main.z, 20, 0.2, 0.25, 0.2, 0.03);
            monde.sendParticles(ParticleTypes.LAVA, main.x, main.y, main.z, 6, 0.15, 0.15, 0.15, 0);
            son(joueur, SoundEvents.FIRECHARGE_USE, 0.9F, 0.7F);
            son(joueur, SoundEvents.LAVA_AMBIENT, 1F, 1F);
        }
    }

    static void endormir(ServerPlayer joueur) {
        EVEILLES.remove(joueur.getUUID());
        // le repos (1 ou 2 minutes) dure plus que les recharges : au réveil, tout est prêt
        PRET_JET.remove(joueur.getUUID());
        PRET_CHOC.remove(joueur.getUUID());
    }

    private static boolean eveille(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    /** Son niveau en ce moment : 0 endormi, 1 la main, 2 la moitié du corps. */
    private static int niveau(ServerPlayer joueur) {
        return eveille(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    private static void son(ServerPlayer joueur, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getEyeY(), joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur);
    }

    /** La main du fruit, bras tendu devant : la gauche pour la lave, la droite pour la glace. */
    private static Vec3 main(ServerPlayer joueur, boolean glace) {
        Vec3 regard = joueur.getLookAngle();
        Vec3 droite = new Vec3(-regard.z, 0, regard.x).normalize();
        return joueur.getEyePosition().add(regard.scale(0.55)).add(droite.scale(glace ? 0.34 : -0.34)).add(0, -0.4, 0);
    }

    private static DamageSource source(ServerPlayer joueur, ResourceKey<DamageType> type) {
        return new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), joueur);
    }

    private static void blesser(LivingEntity cible, DamageSource source, float degats) {
        dansPouvoir = true;
        try {
            cible.hurt(source, degats);
        } finally {
            dansPouvoir = false;
        }
    }

    /** Le froid : la cible se couvre de givre (comme dans la neige poudreuse), sans en prendre les dégâts. */
    private static void givrer(LivingEntity cible) {
        cible.setTicksFrozen(Math.max(cible.getTicksFrozen(), cible.getTicksRequiredToFreeze() - 1));
    }

    // ================================================================ clic droit : le jet

    private static void jet(ServerPlayer joueur) {
        if (!eveille(joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < PRET_JET.getOrDefault(joueur.getUUID(), 0)) {
            return;
        }
        boolean glace = GLACE.equals(Pouvoirs.pouvoir(joueur));
        if (niveau(joueur) >= 2) {
            PRET_JET.put(joueur.getUUID(), maintenant + RECHARGE_JET_2);
            jet2(joueur, glace);
            return;
        }
        PRET_JET.put(joueur.getUUID(), maintenant + RECHARGE_JET);
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(PORTEE_JET));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, fin, new AABB(oeil, fin).inflate(1),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !l.isSpectator(), 0.6F);
        if (touche != null) {
            fin = touche.getLocation();
        }
        envoyer(monde, joueur.position(), new Trait(joueur.getUUID(), glace ? GLACE : LAVE, v(main(joueur, glace)), v(fin)));
        if (glace) {
            son(joueur, SoundEvents.PLAYER_HURT_FREEZE, 1F, 0.6F);
            son(joueur, SoundEvents.GLASS_BREAK, 0.7F, 1.6F);
        } else {
            son(joueur, SoundEvents.BLAZE_SHOOT, 1F, 0.6F);
            son(joueur, SoundEvents.LAVA_POP, 1.2F, 0.8F);
        }
        if (touche != null && touche.getEntity() instanceof LivingEntity cible) {
            blesser(cible, source(joueur, glace ? GIVRE : MAGMA), DEGATS_JET);
            if (glace) {
                cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 2));
                givrer(cible);
            } else {
                cible.igniteForSeconds(5);
            }
        }
    }

    // ================================================================ shift : l'éruption, l'ère glaciaire

    private static void choc(ServerPlayer joueur) {
        if (!eveille(joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < PRET_CHOC.getOrDefault(joueur.getUUID(), 0)) {
            return;
        }
        boolean glace = GLACE.equals(Pouvoirs.pouvoir(joueur));
        if (niveau(joueur) >= 2) {
            PRET_CHOC.put(joueur.getUUID(), maintenant + RECHARGE_CHOC_2);
            choc2(joueur, glace);
            return;
        }
        PRET_CHOC.put(joueur.getUUID(), maintenant + RECHARGE_CHOC);
        ServerLevel monde = joueur.serverLevel();
        Vec3 centre = joueur.position();
        envoyer(monde, centre, new Onde(joueur.getUUID(), glace ? GLACE : LAVE, v(centre)));
        if (glace) {
            monde.playSound(null, joueur.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.6F, 0.5F);
            monde.playSound(null, joueur.blockPosition(), SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 1.4F, 0.6F);
            monde.playSound(null, joueur.blockPosition(), SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 1.2F, 0.5F);
        } else {
            monde.playSound(null, joueur.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 0.7F);
            monde.playSound(null, joueur.blockPosition(), SoundEvents.LAVA_EXTINGUISH, SoundSource.PLAYERS, 1.4F, 0.6F);
            monde.playSound(null, joueur.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1F, 0.5F);
        }
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON_CHOC, 3, RAYON_CHOC),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 loin = cible.position().subtract(centre);
            if (loin.horizontalDistance() > RAYON_CHOC) {
                continue;
            }
            if (glace) {
                blesser(cible, source(joueur, GIVRE), DEGATS_GLACIAIRE);
                geler(cible, DUREE_GEL);
            } else {
                blesser(cible, source(joueur, MAGMA), DEGATS_ERUPTION);
                cible.igniteForSeconds(5);
                Vec3 dehors = loin.horizontalDistanceSqr() < 0.01 ? Vec3.ZERO : new Vec3(loin.x, 0, loin.z).normalize();
                cible.setDeltaMovement(dehors.x * 0.45, 0.7, dehors.z * 0.45);
                cible.hurtMarked = true;
            }
        }
    }

    /** L'ère glaciaire (2 s) ou le faisan (3 s) : pris dans un bloc de glace, il ne bouge plus. */
    private static void geler(LivingEntity cible, int ticks) {
        ServerLevel monde = (ServerLevel) cible.level();
        GELES.put(cible.getId(), new Gele(monde, cible.position(), monde.getServer().getTickCount() + ticks));
        cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks + 20, 1));
        givrer(cible);
        envoyer(monde, cible.position(), new Gel(cible.getId(), ticks));
    }

    // ================================================================ l'éveil 2 : les tirs

    /** Un tir de l'éveil 2, qui vole pour de vrai tick après tick : sa sorte, où il est, vers où, ce qu'il a déjà touché. */
    private static final class Tir {
        final int id;
        final UUID tireur;
        final ServerLevel monde;
        final int sorte;
        final Vec3 dir;
        final double vitesse;
        /** La pluie d'où vient une météore : combien de fois chaque cible a déjà été touchée (3 au plus). */
        final Map<Integer, Integer> pluie;
        final Set<Integer> touches = new HashSet<>();
        Vec3 pos;
        double reste;

        Tir(int id, ServerPlayer tireur, int sorte, Vec3 pos, Vec3 dir, double vitesse, double portee, Map<Integer, Integer> pluie) {
            this.id = id;
            this.tireur = tireur.getUUID();
            this.monde = tireur.serverLevel();
            this.sorte = sorte;
            this.pos = pos;
            this.dir = dir.normalize();
            this.vitesse = vitesse;
            this.reste = portee;
            this.pluie = pluie;
        }
    }

    private static final List<Tir> TIRS = new ArrayList<>();
    private static int prochainTir;
    /** La largeur de chaque sorte de tir : ce qui passe à moins de ça est touché. */
    private static final double[] LARGEURS = {1.3, 0.6, 0.5, 1.8};

    /** Une pluie de météores : autour de quel point, depuis quand, et combien de fois chaque cible a été touchée. */
    private record Pluie(UUID tireur, ServerLevel monde, Vec3 centre, int debut, Map<Integer, Integer> coups) {
    }

    private static final List<Pluie> PLUIES = new ArrayList<>();

    private static void lancer(ServerPlayer joueur, int sorte, Vec3 pos, Vec3 dir, double vitesse, double portee, Map<Integer, Integer> pluie) {
        Tir t = new Tir(prochainTir++, joueur, sorte, pos, dir, vitesse, portee, pluie);
        TIRS.add(t);
        envoyer(t.monde, pos, new Vol(t.id, sorte, v(pos), v(t.dir), (float) vitesse, (float) portee));
    }

    /** Clic droit, au niveau 2 : le grand poing de magma, ou cinq lances de glace en éventail. */
    private static void jet2(ServerPlayer joueur, boolean glace) {
        Vec3 regard = joueur.getLookAngle();
        if (glace) {
            for (int k = -2; k <= 2; k++) {
                float a = k * 12 * Mth.DEG_TO_RAD;
                Vec3 dir = new Vec3(regard.x * Mth.cos(a) - regard.z * Mth.sin(a), regard.y, regard.x * Mth.sin(a) + regard.z * Mth.cos(a));
                lancer(joueur, LANCE, main(joueur, true), dir, 2, PORTEE_2, null);
            }
            son(joueur, SoundEvents.GLASS_BREAK, 1F, 1.3F);
            son(joueur, SoundEvents.SNOW_GOLEM_SHOOT, 1.2F, 0.6F);
        } else {
            lancer(joueur, POING, main(joueur, false).add(regard), regard, 1.6, PORTEE_2, null);
            son(joueur, SoundEvents.BLAZE_SHOOT, 1.2F, 0.5F);
            son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 1.4F);
        }
    }

    /** Shift, au niveau 2 : la pluie de météores là où il vise, ou le faisan de glace droit devant. */
    private static void choc2(ServerPlayer joueur, boolean glace) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle();
        if (glace) {
            Vec3 dir = new Vec3(regard.x, Mth.clamp(regard.y, -0.25, 0.25), regard.z);
            Vec3 depart = joueur.getEyePosition().add(0, -0.5, 0).add(new Vec3(regard.x, 0, regard.z).normalize().scale(1.5));
            lancer(joueur, FAISAN, depart, dir, 1.2, PORTEE_2, null);
            son(joueur, SoundEvents.PHANTOM_FLAP, 1.6F, 0.6F);
            son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 1.2F);
            son(joueur, SoundEvents.PHANTOM_AMBIENT, 1.4F, 1.7F);
            son(joueur, SoundEvents.GLASS_BREAK, 1.2F, 0.6F);
            son(joueur, SoundEvents.POWDER_SNOW_BREAK, 1.4F, 0.5F);
            return;
        }
        Vec3 oeil = joueur.getEyePosition(), loin = oeil.add(regard.scale(PORTEE_PLUIE));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 centre = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        // la zone est au sol, même s'il vise un peu en l'air
        double ySol = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(centre.x), Mth.floor(centre.z));
        if (Math.abs(ySol - centre.y) < 16) {
            centre = new Vec3(centre.x, ySol, centre.z);
        }
        PLUIES.add(new Pluie(joueur.getUUID(), monde, centre, joueur.server.getTickCount(), new HashMap<>()));
        // la zone qui rougeoie au sol, le nuage de cendres au-dessus (dessinés chez chacun)
        envoyer(monde, centre, new Onde(joueur.getUUID(), PLUIE, v(centre)));
        // il frappe vers le ciel : une colonne de lave qui monte
        envoyer(monde, joueur.position(), new Trait(joueur.getUUID(), LAVE, v(main(joueur, false)), v(main(joueur, false).add(0, 16, 0))));
        son(joueur, SoundEvents.BLAZE_SHOOT, 1.4F, 0.4F);
        son(joueur, SoundEvents.FIRECHARGE_USE, 1.2F, 0.5F);
    }

    /** Les tirs avancent d'un tick ; les pluies font tomber leurs météores (une toutes les 3 ticks, pendant 2,5 s). */
    private static void tirs(MinecraftServer server, int maintenant) {
        for (Pluie p : new ArrayList<>(PLUIES)) {
            int age = maintenant - p.debut();
            ServerPlayer tireur = server.getPlayerList().getPlayer(p.tireur());
            if (age > 75 || tireur == null || tireur.level() != p.monde()) {
                PLUIES.remove(p);
                continue;
            }
            if (age >= 10 && age <= 60 && age % 3 == 0) {
                RandomSource hasard = tireur.getRandom();
                double a = hasard.nextDouble() * Mth.TWO_PI, d = Math.sqrt(hasard.nextDouble()) * 6;
                int x = Mth.floor(p.centre().x + Math.cos(a) * d), z = Mth.floor(p.centre().z + Math.sin(a) * d);
                double y = p.monde().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (Math.abs(y - p.centre().y) > 12) {
                    y = p.centre().y;
                }
                Vec3 sol = new Vec3(x + 0.5, y, z + 0.5), depart = sol.add(-8 + hasard.nextDouble() * 3, 30, -5 + hasard.nextDouble() * 3);
                lancer(tireur, METEORE, depart, sol.subtract(depart), 1.8, sol.distanceTo(depart) + 1, p.coups());
            }
        }
        for (Tir t : new ArrayList<>(TIRS)) {
            ServerPlayer tireur = server.getPlayerList().getPlayer(t.tireur);
            if (tireur == null || tireur.level() != t.monde) {
                finir(t, null, false);
                continue;
            }
            double pas = Math.min(t.vitesse, t.reste);
            Vec3 de = t.pos, suivant = de.add(t.dir.scale(pas));
            HitResult bloc = t.monde.clip(new ClipContext(de, suivant, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, tireur));
            Vec3 bout = bloc.getType() == HitResult.Type.MISS ? suivant : bloc.getLocation();
            double l = LARGEURS[t.sorte];
            LivingEntity arret = null;
            for (LivingEntity e : t.monde.getEntitiesOfClass(LivingEntity.class, new AABB(de, bout).inflate(l + 1), e -> Pouvoirs.cible(tireur, e) && !e.isSpectator())) {
                AABB b = e.getBoundingBox().inflate(l);
                if (t.touches.contains(e.getId()) || !b.contains(de) && b.clip(de, bout).isEmpty()) {
                    continue;
                }
                t.touches.add(e.getId());
                if (toucher(tireur, t, e)) {
                    arret = e;
                    break;
                }
            }
            if (arret != null) {
                finir(t, arret.getBoundingBox().getCenter(), true);
                continue;
            }
            t.pos = bout;
            t.reste -= pas;
            if (bloc.getType() != HitResult.Type.MISS || t.reste <= 1e-3) {
                finir(t, t.pos, t.sorte == POING || t.sorte == METEORE);
            }
        }
    }

    /** Ce que fait un tir à ce qu'il touche ; vrai s'il s'arrête là (le faisan, lui, traverse tout). */
    private static boolean toucher(ServerPlayer tireur, Tir t, LivingEntity cible) {
        switch (t.sorte) {
            case LANCE -> {
                blesser(cible, source(tireur, GIVRE), DEGATS_LANCE);
                cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2));
                givrer(cible);
                return true;
            }
            case FAISAN -> {
                blesser(cible, source(tireur, GIVRE), DEGATS_FAISAN);
                geler(cible, GEL_FAISAN);
                return false;
            }
            default -> {
                // le poing et les météores éclatent (voir finir)
                return true;
            }
        }
    }

    /** Le tir s'arrête (contre un mur, une cible, ou au bout de sa course) ; le poing explose, la météore s'écrase. */
    private static void finir(Tir t, Vec3 ou, boolean eclate) {
        TIRS.remove(t);
        Vec3 p = ou == null ? t.pos : ou;
        envoyer(t.monde, p, new Arret(t.id, v(p), eclate));
        ServerPlayer tireur = t.monde.getServer().getPlayerList().getPlayer(t.tireur);
        if (!eclate || tireur == null) {
            return;
        }
        if (t.sorte == POING) {
            t.monde.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 0.8F);
            t.monde.playSound(null, p.x, p.y, p.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.PLAYERS, 1.2F, 0.6F);
            for (LivingEntity e : t.monde.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(2.5), e -> Pouvoirs.cible(tireur, e) && !e.isSpectator())) {
                boolean directe = t.touches.contains(e.getId());
                blesser(e, source(tireur, MAGMA), directe ? DEGATS_POING : DEGATS_EXPLOSION);
                e.igniteForSeconds(directe ? 6 : 3);
                Vec3 dehors = e.position().subtract(p).multiply(1, 0, 1);
                Vec3 d = dehors.lengthSqr() < 0.01 ? t.dir : dehors.normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(d.x * 0.8, 0.45, d.z * 0.8));
                e.hurtMarked = true;
            }
        } else if (t.sorte == METEORE) {
            t.monde.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
            for (LivingEntity e : t.monde.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(2.2), e -> Pouvoirs.cible(tireur, e) && !e.isSpectator())) {
                int deja = t.pluie == null ? 0 : t.pluie.getOrDefault(e.getId(), 0);
                if (deja >= 3) {
                    continue;
                }
                if (t.pluie != null) {
                    t.pluie.put(e.getId(), deja + 1);
                }
                blesser(e, source(tireur, MAGMA), DEGATS_METEORE);
                e.igniteForSeconds(3);
            }
        }
    }


    // ================================================================ les passifs, et ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
            String pouvoir = Pouvoirs.pouvoir(joueur);
            if (!est(pouvoir) || joueur.isSpectator()) {
                continue;
            }
            // l'eau, pour qui a mangé un fruit : un demi-cœur par seconde, comme du poison
            if (joueur.isInWater() && !joueur.isCreative() && maintenant % 20 == 0) {
                joueur.hurt(new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(EAU)), 1);
            }
            if (LAVE.equals(pouvoir)) {
                // hors de la lave, il ne reste pas en feu (ça ne lui fait rien, mais on ne le montre pas)
                if (joueur.isOnFire() && !joueur.isInLava()) {
                    joueur.clearFire();
                }
            } else if (eveille(joueur)) {
                marcherSurLEau(joueur);
            }
        }
        tirs(server, maintenant);
        // la glace de ses pas refond toute seule
        GLACES.entrySet().removeIf(e -> {
            if (maintenant < e.getValue()) {
                return false;
            }
            fondre(e.getKey());
            return true;
        });
        // ceux que l'ère glaciaire tient restent sur place
        for (Map.Entry<Integer, Gele> e : new ArrayList<>(GELES.entrySet())) {
            Gele g = e.getValue();
            Entity cible = g.monde().getEntity(e.getKey());
            if (!(cible instanceof LivingEntity vivant) || !vivant.isAlive() || maintenant >= g.fin()) {
                GELES.remove(e.getKey());
                if (cible != null) {
                    g.monde().playSound(null, cible.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.9F);
                    g.monde().sendParticles(ParticleTypes.SNOWFLAKE, cible.getX(), cible.getY(0.5), cible.getZ(), 30, 0.5, 0.8, 0.5, 0.06);
                }
                continue;
            }
            if (cible.position().distanceToSqr(g.pos()) > 0.0025) {
                cible.teleportTo(g.pos().x, g.pos().y, g.pos().z);
            }
            cible.setDeltaMovement(Vec3.ZERO);
            cible.hurtMarked = true;
            cible.fallDistance = 0;
        }
    }

    /** Celui de la glace, éveillé : l'eau gèle sous ses pieds (comme les bottes Semelles givrantes), et refond derrière lui. */
    private static void marcherSurLEau(ServerPlayer joueur) {
        if (!joueur.onGround()) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        int fonte = joueur.server.getTickCount() + FONTE;
        BlockState glace = Blocks.FROSTED_ICE.defaultBlockState();
        BlockPos pieds = joueur.blockPosition();
        int r = 2;
        for (BlockPos p : BlockPos.betweenClosed(pieds.offset(-r, -1, -r), pieds.offset(r, -1, r))) {
            if (!p.closerToCenterThan(joueur.position(), r + 0.5)) {
                continue;
            }
            BlockState etat = monde.getBlockState(p);
            if (etat.is(Blocks.FROSTED_ICE)) {
                // tant qu'il est dessus ou tout près, elle tient
                if (etat.getValue(FrostedIceBlock.AGE) > 0) {
                    monde.setBlockAndUpdate(p, etat.setValue(FrostedIceBlock.AGE, 0));
                }
                GLACES.replace(new Glace(monde, p.immutable()), fonte);
                continue;
            }
            if (etat.is(Blocks.WATER) && etat.getValue(LiquidBlock.LEVEL) == 0 && monde.getBlockState(p.above()).isAir()
                    && glace.canSurvive(monde, p) && monde.isUnobstructed(glace, p, CollisionContext.empty())) {
                monde.setBlockAndUpdate(p.immutable(), glace);
                monde.scheduleTick(p.immutable(), Blocks.FROSTED_ICE, Mth.nextInt(joueur.getRandom(), 60, 120));
                GLACES.put(new Glace(monde, p.immutable()), fonte);
            }
        }
    }

    /** La glace redevient de l'eau (si elle est encore là : de jour, elle a pu fondre plus tôt d'elle-même). */
    private static void fondre(Glace g) {
        if (g.monde().getBlockState(g.pos()).is(Blocks.FROSTED_ICE)) {
            g.monde().setBlockAndUpdate(g.pos(), Blocks.WATER.defaultBlockState());
        }
    }

    /**
     * Le feu et la lave ne font rien à celui de la lave, ni le gel à celui de la glace. Et le clic gauche d'un fruit
     * éveillé : n'importe quelle arme frappe un peu plus fort, et la cible brûle (ou ralentit).
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (event.getEntity() instanceof ServerPlayer victime) {
            String pouvoir = Pouvoirs.pouvoir(victime);
            if (LAVE.equals(pouvoir) && source.is(DamageTypeTags.IS_FIRE) || GLACE.equals(pouvoir) && source.is(DamageTypeTags.IS_FREEZING)) {
                event.setCanceled(true);
                return;
            }
            // l'éveil 2 : qui le frappe de près prend feu (ou gèle)
            if (!dansPouvoir && niveau(victime) >= 2 && source.getEntity() instanceof LivingEntity agresseur && source.getDirectEntity() == agresseur
                    && agresseur != victime && agresseur.distanceTo(victime) < 6) {
                if (LAVE.equals(pouvoir)) {
                    agresseur.igniteForSeconds(3);
                } else {
                    agresseur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
                    givrer(agresseur);
                }
            }
        }
        if (dansPouvoir || !(source.getEntity() instanceof ServerPlayer joueur) || source.getDirectEntity() != joueur
                || !source.is(DamageTypes.PLAYER_ATTACK) || !eveille(joueur)) {
            return;
        }
        LivingEntity cible = event.getEntity();
        boolean fort = niveau(joueur) >= 2, glace = GLACE.equals(Pouvoirs.pouvoir(joueur));
        event.setAmount(event.getAmount() + (fort ? BONUS_ARME_2 : BONUS_ARME));
        ServerLevel monde = joueur.serverLevel();
        if (glace) {
            cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, fort ? 80 : 60, fort ? 1 : 0));
            givrer(cible);
            monde.sendParticles(ParticleTypes.SNOWFLAKE, cible.getX(), cible.getY(0.6), cible.getZ(), 14, 0.3, 0.4, 0.3, 0.04);
        } else {
            cible.igniteForSeconds(fort ? 6 : 4);
            monde.sendParticles(ParticleTypes.FLAME, cible.getX(), cible.getY(0.6), cible.getZ(), 12, 0.3, 0.4, 0.3, 0.03);
        }
        if (fort) {
            eclabousser(joueur, cible, glace);
        }
    }

    /** L'éveil 2 : le coup éclabousse de lave (ou le givre s'étend) sur ce qui est à 2 blocs de la cible. */
    private static void eclabousser(ServerPlayer joueur, LivingEntity cible, boolean glace) {
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, cible.getBoundingBox().inflate(2.5),
                e -> e != cible && Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            if (e.distanceTo(cible) > 2.5) {
                continue;
            }
            blesser(e, source(joueur, glace ? GIVRE : MAGMA), ECLABOUSSURE);
            if (glace) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0));
                givrer(e);
                monde.sendParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getY(0.5), e.getZ(), 10, 0.3, 0.4, 0.3, 0.03);
            } else {
                e.igniteForSeconds(3);
                monde.sendParticles(ParticleTypes.LAVA, e.getX(), e.getY(0.5), e.getZ(), 4, 0.2, 0.3, 0.2, 0);
            }
        }
        monde.sendParticles(glace ? ParticleTypes.SNOWFLAKE : ParticleTypes.LAVA, cible.getX(), cible.getY(0.5), cible.getZ(), glace ? 30 : 8, 1, 0.3, 1, 0.05);
    }

    /** Dans l'eau, un fruit du démon ne guérit pas : sinon, le ventre plein, on regagnerait plus vite que l'eau ne prend. */
    @SubscribeEvent
    public static void soin(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && joueur.isInWater() && est(Pouvoirs.pouvoir(joueur))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        EVEILLES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        PRET_JET.clear();
        PRET_CHOC.clear();
        GELES.clear();
        TIRS.clear();
        PLUIES.clear();
        GLACES.keySet().forEach(Fruit::fondre);
        GLACES.clear();
    }

    /** Une petite gerbe de poussière de la couleur du fruit (pour le client aussi). */
    public static DustParticleOptions poussiere(boolean glace, float taille) {
        return new DustParticleOptions(glace ? new Vector3f(0.72F, 0.9F, 1F) : new Vector3f(1F, 0.45F, 0.08F), taille);
    }

    private Fruit() {
    }
}
