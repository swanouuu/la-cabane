package fr.cabane.muscu;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Un haltère (de 5 à 30 kg) : clic droit pour faire une série de curls. Un deuxième dans l'autre main, et on alterne. */
public class HaltereItem extends Item {
    public final int kg;

    public HaltereItem(Properties properties, int kg) {
        super(properties);
        this.kg = kg;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
        ItemStack tenu = joueur.getItemInHand(main);
        if (joueur.isPassenger()) {
            return InteractionResultHolder.pass(tenu);
        }
        if (level.isClientSide) {
            fr.cabane.client.muscu.ClientMuscu.haltere(kg);
        }
        return InteractionResultHolder.consume(tenu);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lignes, TooltipFlag flag) {
        int[] g = Machine.gainHaltere(kg);
        lignes.add(Component.literal("Série réussie : Force " + (g[0] == 1 ? "I" : "II") + " pendant " + Muscu.duree(g[1])).withStyle(ChatFormatting.RED));
    }
}
