package fr.cabane.fumee;

import com.mojang.serialization.MapCodec;
import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
import fr.cabane.CabaneItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3f;

/**
 * La puff (un clic droit, un nuage parfumé, puis il faut attendre un peu) et la chicha : on la pose,
 * clic droit pour prendre un tuyau, et tant qu'on reste à moins de 10 blocs, clic droit avec le
 * tuyau pour souffler un gros nuage. Trop loin, le tuyau se décroche.
 */
public final class Fumee {
    public static final int RAYON_CHICHA = 10;

    /** Un nuage de fumée devant la bouche du joueur, teinté selon le parfum. */
    static void souffler(ServerPlayer joueur, Vector3f teinte, int volume) {
        ServerLevel level = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle();
        Vec3 bouche = joueur.getEyePosition().add(regard.scale(0.35)).add(0, -0.12, 0);
        for (int i = 0; i < volume; i++) {
            double v = 0.02 + joueur.getRandom().nextDouble() * 0.05;
            level.sendParticles(ParticleTypes.CLOUD, bouche.x, bouche.y, bouche.z, 0, regard.x, regard.y + 0.15, regard.z, v);
        }
        level.sendParticles(new DustParticleOptions(teinte, 1.6F), bouche.x, bouche.y, bouche.z, volume, 0.15, 0.1, 0.15, 0.01);
        if (volume > 12) {
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, bouche.x, bouche.y, bouche.z, 2, 0.1, 0.05, 0.1, 0.005);
        }
        level.playSound(null, bouche.x, bouche.y, bouche.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.25F, 1.6F + joueur.getRandom().nextFloat() * 0.3F);
    }

    /** La puff : un parfum, une couleur de fumée ; elle finit par être vide. */
    public static class PuffItem extends Item {
        private final Vector3f teinte;
        private final String parfum;

        public PuffItem(Properties properties, int couleur, String parfum) {
            super(properties);
            this.teinte = new Vector3f((couleur >> 16 & 0xFF) / 255F, (couleur >> 8 & 0xFF) / 255F, (couleur & 0xFF) / 255F);
            this.parfum = parfum;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer joueur) {
                souffler(joueur, teinte, 8);
                stack.hurtAndBreak(1, joueur, hand == InteractionHand.MAIN_HAND
                        ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            }
            player.getCooldowns().addCooldown(this, 50);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("Parfum " + parfum).withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltip.add(Component.literal((stack.getMaxDamage() - stack.getDamageValue()) + " bouffées restantes").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Le tuyau, accroché à une chicha précise. */
    public static class TuyauItem extends Item {
        public TuyauItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer joueur) {
                BlockPos chicha = stack.get(CabaneComponents.CHICHA);
                if (chicha == null || !(level.getBlockState(chicha).getBlock() instanceof ChichaBlock)
                        || chicha.distToCenterSqr(joueur.position()) > RAYON_CHICHA * RAYON_CHICHA) {
                    decrocher(joueur, stack);
                } else {
                    souffler(joueur, new Vector3f(0.9F, 0.9F, 0.95F), 18);
                    level.playSound(null, chicha, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.BLOCKS, 0.8F, 0.8F);
                    ((ServerLevel) level).sendParticles(ParticleTypes.BUBBLE_POP, chicha.getX() + 0.5, chicha.getY() + 0.25, chicha.getZ() + 0.5, 6, 0.1, 0.05, 0.1, 0.01);
                }
            }
            player.getCooldowns().addCooldown(this, 40);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("Reste à moins de " + RAYON_CHICHA + " blocs de la chicha.").withStyle(ChatFormatting.GRAY));
        }
    }

    static void decrocher(ServerPlayer joueur, ItemStack tuyau) {
        tuyau.setCount(0);
        joueur.displayClientMessage(Component.literal("Le tuyau s'est décroché de la chicha.").withStyle(ChatFormatting.GRAY), true);
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.LEASH_KNOT_BREAK, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /** La chicha : clic droit main vide pour prendre un tuyau. */
    public static class ChichaBlock extends HorizontalDirectionalBlock {
        private static final VoxelShape FORME = Block.box(3, 0, 3, 13, 16, 13);

        public ChichaBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return simpleCodec(ChichaBlock::new);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FORME;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide) {
                ItemStack tuyau = new ItemStack(CabaneItems.TUYAU.get());
                tuyau.set(CabaneComponents.CHICHA, pos.immutable());
                if (!player.getInventory().add(tuyau)) {
                    player.drop(tuyau, false);
                }
                level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.BLOCKS, 0.7F, 1.3F);
                player.displayClientMessage(Component.literal("Tu prends un tuyau : clic droit pour tirer dessus, reste dans les " + RAYON_CHICHA + " blocs.")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
            // le charbon fume doucement au sommet
            if (random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 0, 0.01, 0);
            }
        }
    }

    /** Toutes les secondes : un tuyau trop loin de sa chicha se décroche. */
    @EventBusSubscriber(modid = Cabane.MODID)
    public static final class Surveillance {
        @SubscribeEvent
        public static void tick(PlayerTickEvent.Post event) {
            if (!(event.getEntity() instanceof ServerPlayer joueur) || joueur.tickCount % 20 != 0) {
                return;
            }
            for (ItemStack stack : joueur.getInventory().items) {
                if (stack.is(CabaneItems.TUYAU.get())) {
                    BlockPos chicha = stack.get(CabaneComponents.CHICHA);
                    if (chicha == null || chicha.distToCenterSqr(joueur.position()) > RAYON_CHICHA * RAYON_CHICHA
                            || !(joueur.level().getBlockState(chicha).getBlock() instanceof ChichaBlock)) {
                        decrocher(joueur, stack);
                    }
                }
            }
        }
    }

    private Fumee() {
    }
}
