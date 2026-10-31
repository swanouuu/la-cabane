package fr.cabane.tcg;

import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
import fr.cabane.CabaneItems;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le classeur garde les cartes à l'intérieur de l'objet (identifiant → nombre).
 * « Ranger » y met toutes les cartes de l'inventaire, « Sortir » en reprend une pour l'échanger.
 */
public final class Classeur {

    /** Client → serveur : action = 0 ranger tout, 1 sortir la carte {@code carte}. */
    public record Action(int action, String carte) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Cabane.id("classeur_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.stringUtf8(256), Action::carte,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer joueur) {
                agir(joueur, p.action(), p.carte());
            }
        }));
    }

    public static Map<String, Integer> contenu(ItemStack classeur) {
        return classeur.getOrDefault(CabaneComponents.CLASSEUR.get(), Map.of());
    }

    private static ItemStack enMain(ServerPlayer joueur) {
        for (InteractionHand main : InteractionHand.values()) {
            if (joueur.getItemInHand(main).is(CabaneItems.CLASSEUR.get())) {
                return joueur.getItemInHand(main);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void agir(ServerPlayer joueur, int action, String carte) {
        ItemStack classeur = enMain(joueur);
        if (classeur.isEmpty()) {
            return;
        }
        Map<String, Integer> contenu = new HashMap<>(contenu(classeur));
        if (action == 0) {
            int rangees = 0;
            Inventory inv = joueur.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                String id = s.get(CabaneComponents.CARTE.get());
                if (s.is(CabaneItems.CARTE.get()) && id != null) {
                    contenu.merge(id, s.getCount(), Integer::sum);
                    rangees += s.getCount();
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
            joueur.displayClientMessage(Component.literal(rangees + " carte(s) rangée(s) dans le classeur.").withStyle(ChatFormatting.GREEN), true);
        } else if (action == 1 && contenu.getOrDefault(carte, 0) > 0) {
            contenu.merge(carte, -1, Integer::sum);
            contenu.values().removeIf(n -> n <= 0);
            ItemStack sortie = Cartes.carte(carte);
            if (!joueur.getInventory().add(sortie)) {
                joueur.drop(sortie, false);
            }
        }
        classeur.set(CabaneComponents.CLASSEUR.get(), Map.copyOf(contenu));
    }

    private Classeur() {
    }
}
