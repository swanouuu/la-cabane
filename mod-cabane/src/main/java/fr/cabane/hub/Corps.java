package fr.cabane.hub;

import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.gui.CorpseContainerBase;
import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les corps des joueurs (le mod Corpse) : un corps vide se casse d'un coup, par n'importe qui ; un corps plein reste
 * protégé. Son propriétaire a en plus un bouton dans l'écran du corps pour le supprimer et abandonner son stuff.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Corps {
    public record Supprimer() implements CustomPacketPayload {
        public static final Type<Supprimer> TYPE = new Type<>(Cabane.id("corps_supprimer"));
        public static final StreamCodec<ByteBuf, Supprimer> CODEC = StreamCodec.unit(new Supprimer());

        @Override
        public Type<Supprimer> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Supprimer.TYPE, Supprimer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer joueur) {
                supprimer(joueur);
            }
        }));
    }

    @SubscribeEvent
    public static void frapper(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof CorpseEntity corps)) {
            return;
        }
        event.setCanceled(true);
        if (!(event.getEntity() instanceof ServerPlayer joueur)) {
            return;
        }
        if (corps.isEmpty()) {
            casser(corps);
        } else {
            joueur.displayClientMessage(Component.literal("Il reste du stuff dans ce corps."), true);
        }
    }

    public static boolean proprietaire(CorpseEntity corps, UUID joueur) {
        return corps.getDeath() != null && joueur.equals(corps.getDeath().getPlayerUUID());
    }

    /** Le bouton de l'écran : c'est le menu ouvert côté serveur qui dit quel corps, le client ne choisit rien. */
    private static void supprimer(ServerPlayer joueur) {
        if (!(joueur.containerMenu instanceof CorpseContainerBase menu) || menu.isHistory()) {
            return;
        }
        CorpseEntity corps = menu.getCorpse();
        if (!corps.isAlive() || corps.level().getEntity(corps.getId()) != corps || !proprietaire(corps, joueur.getUUID())) {
            return;
        }
        // en disparaissant, un corps lâche par terre tout ce qu'il contient : on le vide d'abord, le stuff est abandonné
        Death mort = corps.getDeath();
        mort.getMainInventory().replaceAll(s -> ItemStack.EMPTY);
        mort.getArmorInventory().replaceAll(s -> ItemStack.EMPTY);
        mort.getOffHandInventory().replaceAll(s -> ItemStack.EMPTY);
        mort.getAdditionalItems().replaceAll(s -> ItemStack.EMPTY);
        joueur.closeContainer();
        casser(corps);
    }

    private static void casser(CorpseEntity corps) {
        corps.level().playSound(null, corps.getX(), corps.getY(), corps.getZ(), SoundEvents.SKELETON_DEATH, SoundSource.NEUTRAL, 0.8F, 0.8F);
        corps.discard();
    }

    private Corps() {
    }
}
