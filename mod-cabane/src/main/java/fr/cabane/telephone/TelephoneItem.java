package fr.cabane.telephone;

import fr.cabane.client.ClientPhone;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class TelephoneItem extends Item {
    public TelephoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            ClientPhone.openScreen();
        } else if (player instanceof ServerPlayer serverPlayer) {
            PhoneCalls.sendState(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String numero = stack.get(fr.cabane.CabaneComponents.NUMERO);
        tooltip.add(numero == null
                ? Component.literal("SIM à activer : choisis ton numéro (définitif)").withStyle(ChatFormatting.YELLOW)
                : Component.literal("☎ " + numero).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.cabane.telephone.desc").withStyle(ChatFormatting.GRAY));
    }
}
