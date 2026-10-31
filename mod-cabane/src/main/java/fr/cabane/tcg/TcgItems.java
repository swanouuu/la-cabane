package fr.cabane.tcg;

import fr.cabane.CabaneComponents;
import fr.cabane.casino.Ticket;
import fr.cabane.client.ClientCartes;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Les objets du jeu de cartes (carte, booster, classeur) et le ticket à gratter. */
public final class TcgItems {

    /** Une carte : son identifiant est dans le composant CARTE, son nom vient du catalogue. */
    public static class CarteItem extends Item {
        public CarteItem(Properties properties) {
            super(properties);
        }

        @Override
        public Component getName(ItemStack stack) {
            Cartes.Def def = Cartes.def(stack.getOrDefault(CabaneComponents.CARTE.get(), ""));
            return def == null ? super.getName(stack) : Component.literal(def.nom()).withStyle(Cartes.COULEURS[def.rarete()]);
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            Cartes.Def def = Cartes.def(stack.getOrDefault(CabaneComponents.CARTE.get(), ""));
            return def != null && def.rarete() >= 2;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            Cartes.Def def = Cartes.def(stack.getOrDefault(CabaneComponents.CARTE.get(), ""));
            if (def == null) {
                tooltip.add(Component.literal("Carte inconnue").withStyle(ChatFormatting.DARK_GRAY));
                return;
            }
            tooltip.add(Component.literal(Cartes.RARETES[def.rarete()]).withStyle(Cartes.COULEURS[def.rarete()]));
            tooltip.add(Component.literal(def.description()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal(def.serie() + " — n° " + def.numero() + "/" + Cartes.toutes().size()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public static class BoosterItem extends Item {
        public BoosterItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer joueur && Booster.ouvrir(joueur)) {
                stack.consume(1, player);
                level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1F, 0.8F);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("5 cartes, dont au moins une rare. Clic droit pour ouvrir.").withStyle(ChatFormatting.GRAY));
        }
    }

    public static class ClasseurItem extends Item {
        public ClasseurItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            if (level.isClientSide) {
                ClientCartes.ouvrirClasseur();
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            Map<String, Integer> contenu = Classeur.contenu(stack);
            tooltip.add(Component.literal(contenu.size() + " / " + Cartes.toutes().size() + " cartes différentes").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("Clic droit pour l'ouvrir et ranger tes cartes.").withStyle(ChatFormatting.GRAY));
        }
    }

    public static class TicketItem extends Item {
        public TicketItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer joueur) {
                stack.consume(1, player);
                Ticket.gratter(joueur);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("Trois montants identiques = gagné ! Jusqu'à 1 000 $.").withStyle(ChatFormatting.GRAY));
        }
    }

    private TcgItems() {
    }
}
