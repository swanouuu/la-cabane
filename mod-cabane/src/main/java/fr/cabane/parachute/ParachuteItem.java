package fr.cabane.parachute;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Clic droit en tombant : le parachute s'ouvre. Il s'use un peu à chaque saut. */
public class ParachuteItem extends Item {
    public ParachuteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.onGround() || player.isPassenger() || player.isInWater()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Saute d'abord, puis ouvre le parachute en l'air !").withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            ParachuteEntity.deployer(player);
            level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_ELYTRA.value(), SoundSource.PLAYERS, 1F, 0.8F);
            stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("En chute : clic droit pour l'ouvrir.").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("ZQSD pour te diriger, accroupi pour couper.").withStyle(ChatFormatting.GRAY));
    }
}
