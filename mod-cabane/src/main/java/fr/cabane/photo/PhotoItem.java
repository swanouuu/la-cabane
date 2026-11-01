package fr.cabane.photo;

import fr.cabane.CabaneComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/** Une photo imprimée : clic droit sur un mur pour l'accrocher, comme un tableau. */
public class PhotoItem extends Item {
    public PhotoItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        PhotoImprimee photo = stack.get(CabaneComponents.PHOTO.get());
        Direction face = context.getClickedFace();
        if (photo == null || !face.getAxis().isHorizontal()) {
            return InteractionResult.FAIL;
        }
        BlockPos pos = context.getClickedPos().relative(face);
        Player joueur = context.getPlayer();
        if (joueur != null && !joueur.mayUseItemAt(pos, face, stack)) {
            return InteractionResult.FAIL;
        }
        Level level = context.getLevel();
        TableauPhoto tableau = new TableauPhoto(level, pos, face, photo);
        if (!tableau.survives()) {
            if (joueur != null && !level.isClientSide) {
                joueur.displayClientMessage(Component.literal("Pas la place pour une photo de " + photo.largeur() + " × " + photo.hauteur()
                        + " ici : il faut un mur plein derrière.").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            tableau.playPlacementSound();
            level.gameEvent(joueur, GameEvent.ENTITY_PLACE, tableau.position());
            level.addFreshEntity(tableau);
        }
        stack.consume(1, joueur);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PhotoImprimee photo = stack.get(CabaneComponents.PHOTO.get());
        if (photo != null) {
            tooltip.add(Component.literal(photo.largeur() + " × " + photo.hauteur() + " blocs").withStyle(ChatFormatting.GOLD));
        }
        tooltip.add(Component.literal("Clic droit sur un mur pour l'accrocher.").withStyle(ChatFormatting.GRAY));
    }
}
