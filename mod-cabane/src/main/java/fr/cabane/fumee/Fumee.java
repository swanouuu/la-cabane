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

    /** Les goûts, et la couleur de leur fumée. */
    public record Gout(String nom, int couleur) {
    }

    public static final List<Gout> GOUTS = List.of(
            new Gout("Love 66", 0xFF6FA8), new Gout("Lady Killer", 0xFFB347), new Gout("Double Pomme", 0xB3E05A), new Gout("Blue Mist", 0x6FA8FF),
            new Gout("Menthe glaciale", 0x9AFFD0), new Gout("Pinkman", 0xFF8FC8), new Gout("Hawaï", 0xFFD75E), new Gout("Mi Amor", 0xFFF27A),
            new Gout("Raisin menthe", 0xA06FFF), new Gout("Pastèque Ice", 0xFF5A6A), new Gout("Ice Bonbon", 0xC0F0FF), new Gout("Fraise Kiwi", 0x7FE08A),
            new Gout("Mangue Tango", 0xFFA040), new Gout("Cola Ice", 0x9A6A4A), new Gout("Barbe à papa", 0xFFC0E8), new Gout("Bubble Gum", 0xFF9AD8),
            new Gout("Blue Razz", 0x4A8AFF), new Gout("Pêche glacée", 0xFFB894), new Gout("Citron vert", 0xC8FF5A), new Gout("Fruit du dragon", 0xE0409A),
            new Gout("Cactus", 0x5FBF6F), new Gout("Grenade", 0xC0203A), new Gout("Myrtille framboise", 0x8A4AD0), new Gout("Pink Lemonade", 0xFFA0B8));

    static Gout gout(String nom) {
        return GOUTS.stream().filter(g -> g.nom().equals(nom)).findFirst().orElse(GOUTS.get(0));
    }

    /** Le goût de cet objet ; un goût surprise est tiré la première fois. */
    static Gout goutDe(ItemStack stack, net.minecraft.util.RandomSource hasard) {
        String nom = stack.get(CabaneComponents.GOUT);
        if (nom == null) {
            nom = GOUTS.get(hasard.nextInt(GOUTS.size())).nom();
            stack.set(CabaneComponents.GOUT, nom);
        }
        return gout(nom);
    }

    static Vector3f teinte(Gout g) {
        int c = g.couleur();
        return new Vector3f((c >> 16 & 0xFF) / 255F, (c >> 8 & 0xFF) / 255F, (c & 0xFF) / 255F);
    }

    /** Un nuage de fumée devant la bouche du joueur, teinté selon le goût, et parfois un rond de fumée. */
    public static void souffler(ServerPlayer joueur, Vector3f teinte, int volume) {
        ServerLevel level = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle();
        Vec3 bouche = joueur.getEyePosition().add(regard.scale(0.35)).add(0, -0.12, 0);
        net.minecraft.util.RandomSource r = joueur.getRandom();
        for (int i = 0; i < volume; i++) {
            double v = 0.02 + r.nextDouble() * 0.06;
            Vec3 d = regard.add((r.nextDouble() - 0.5) * 0.4, 0.1 + r.nextDouble() * 0.15, (r.nextDouble() - 0.5) * 0.4);
            level.sendParticles(ParticleTypes.CLOUD, bouche.x, bouche.y, bouche.z, 0, d.x, d.y, d.z, v);
        }
        level.sendParticles(new DustParticleOptions(teinte, 2.2F), bouche.x, bouche.y, bouche.z, volume * 2, 0.25, 0.15, 0.25, 0.01);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, bouche.x, bouche.y, bouche.z, Math.max(1, volume / 6), 0.12, 0.05, 0.12, 0.004);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, bouche.x, bouche.y, bouche.z, volume, 0.2, 0.1, 0.2, 0.02);
        if (r.nextInt(3) == 0) {
            // un rond de fumée qui part devant soi
            Vec3 haut = new Vec3(0, 1, 0);
            Vec3 a = regard.cross(haut).normalize();
            Vec3 b = regard.cross(a).normalize();
            Vec3 centre = bouche.add(regard.scale(0.3));
            for (int i = 0; i < 18; i++) {
                double t = i * Math.PI * 2 / 18;
                Vec3 p = centre.add(a.scale(Math.cos(t) * 0.22)).add(b.scale(Math.sin(t) * 0.22));
                level.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 0, regard.x, regard.y, regard.z, 0.05);
            }
        }
        level.playSound(null, bouche.x, bouche.y, bouche.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.25F, 1.6F + r.nextFloat() * 0.3F);
    }

    /** Le glouglou : des bulles qui remontent et éclatent, plus grosses et plus graves dans une chicha que dans une puff. */
    static void glouglou(ServerLevel level, double x, double y, double z, boolean chicha) {
        net.minecraft.util.RandomSource r = level.getRandom();
        float grave = chicha ? 0.75F : 1.3F;
        level.playSound(null, x, y, z, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.PLAYERS, chicha ? 0.9F : 0.35F, grave + r.nextFloat() * 0.2F);
        for (int i = 0; i < (chicha ? 4 : 2); i++) {
            level.playSound(null, x, y, z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, chicha ? 0.7F : 0.3F, grave + r.nextFloat() * 0.5F);
        }
    }

    /** La puff : un goût surprise (tiré à la première bouffée), une couleur de fumée ; elle finit par être vide. */
    public static class PuffItem extends Item {
        public PuffItem(Properties properties, int couleur, String parfum) {
            super(properties);
        }

        @Override
        public Component getName(ItemStack stack) {
            String gout = stack.get(CabaneComponents.GOUT);
            return gout == null ? super.getName(stack) : Component.literal("Puff " + gout);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer joueur) {
                boolean nouveau = stack.get(CabaneComponents.GOUT) == null;
                Gout g = goutDe(stack, joueur.getRandom());
                if (nouveau) {
                    joueur.displayClientMessage(Component.literal("Goût surprise : " + g.nom() + " !").withColor(g.couleur()), true);
                }
                souffler(joueur, teinte(g), 16);
                glouglou(joueur.serverLevel(), joueur.getX(), joueur.getEyeY(), joueur.getZ(), false);
                stack.hurtAndBreak(1, joueur, hand == InteractionHand.MAIN_HAND
                        ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            }
            player.getCooldowns().addCooldown(this, 50);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            String gout = stack.get(CabaneComponents.GOUT);
            tooltip.add(gout == null ? Component.literal("Goût surprise !").withStyle(ChatFormatting.LIGHT_PURPLE)
                    : Component.literal("Goût " + gout).withColor(gout(gout).couleur()));
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
                    souffler(joueur, teinte(goutDe(stack, joueur.getRandom())), 34);
                    glouglou((ServerLevel) level, chicha.getX() + 0.5, chicha.getY() + 0.5, chicha.getZ() + 0.5, true);
                    ((ServerLevel) level).sendParticles(ParticleTypes.BUBBLE_POP, chicha.getX() + 0.5, chicha.getY() + 0.25, chicha.getZ() + 0.5, 6, 0.1, 0.05, 0.1, 0.01);
                }
            }
            player.getCooldowns().addCooldown(this, 40);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            String gout = stack.get(CabaneComponents.GOUT);
            if (gout != null) {
                tooltip.add(Component.literal("Goût " + gout).withColor(gout(gout).couleur()));
            }
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
                Gout g = goutDe(tuyau, level.random);
                if (!player.getInventory().add(tuyau)) {
                    player.drop(tuyau, false);
                }
                level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.BLOCKS, 0.7F, 1.3F);
                player.displayClientMessage(Component.literal("Ce soir à la chicha : " + g.nom() + ". Clic droit pour tirer, reste dans les " + RAYON_CHICHA + " blocs.")
                        .withColor(g.couleur()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
            // le charbon fume au sommet, l'eau fait des bulles dans le vase
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0.015, 0);
            if (random.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0.012, 0);
            }
            if (random.nextInt(4) == 0) {
                level.addParticle(ParticleTypes.BUBBLE_POP, pos.getX() + 0.4 + random.nextDouble() * 0.2, pos.getY() + 0.3, pos.getZ() + 0.4 + random.nextDouble() * 0.2, 0, 0.02, 0);
            }
            if (random.nextInt(5) == 0) {
                level.addParticle(ParticleTypes.SMALL_FLAME, pos.getX() + 0.5, pos.getY() + 0.95, pos.getZ() + 0.5, 0, 0.005, 0);
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
