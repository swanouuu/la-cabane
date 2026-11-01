package fr.cabane.photo;

import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
import fr.cabane.CabaneItems;
import fr.cabane.telephone.PhoneCalls;
import fr.cabane.telephone.Piaf;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Imprimer une photo du téléphone : elle sort en vrai, à accrocher au mur, à la taille qu'on veut. */
public final class Impression {
    /** La plus grande photo : 8 x 8 blocs. */
    public static final int MAX = 8;

    /** Client → serveur : imprimer cette photo de la galerie, à cette taille (en blocs). */
    public record Demande(long photo, int largeur, int hauteur) implements CustomPacketPayload {
        public static final Type<Demande> TYPE = new Type<>(Cabane.id("imprimer_photo"));
        public static final StreamCodec<ByteBuf, Demande> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Demande::photo,
                ByteBufCodecs.VAR_INT, Demande::largeur,
                ByteBufCodecs.VAR_INT, Demande::hauteur,
                Demande::new);

        @Override
        public Type<Demande> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Demande.TYPE, Demande.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer joueur) {
                imprimer(joueur, p);
            }
        }));
    }

    /** Une feuille de papier pour quatre blocs de photo (au moins une). */
    public static int papier(int largeur, int hauteur) {
        return Math.max(1, (largeur * hauteur + 3) / 4);
    }

    private static void imprimer(ServerPlayer joueur, Demande d) {
        String sim = PhoneCalls.numero(joueur);
        if (sim == null || !Piaf.dansGalerie(joueur.server, sim, d.photo())) {
            return;
        }
        int largeur = Math.clamp(d.largeur(), 1, MAX);
        int hauteur = Math.clamp(d.hauteur(), 1, MAX);
        int cout = papier(largeur, hauteur);
        if (!joueur.getAbilities().instabuild) {
            if (joueur.getInventory().countItem(Items.PAPER) < cout) {
                joueur.displayClientMessage(Component.literal("🖨 Il faut " + cout + " feuille" + (cout > 1 ? "s" : "") + " de papier pour imprimer en "
                        + largeur + " × " + hauteur + ".").withStyle(ChatFormatting.RED), true);
                return;
            }
            joueur.getInventory().clearOrCountMatchingItems(s -> s.is(Items.PAPER), cout, joueur.inventoryMenu.getCraftSlots());
        }
        ItemStack photo = new ItemStack(CabaneItems.PHOTO.get());
        photo.set(CabaneComponents.PHOTO.get(), new PhotoImprimee(d.photo(), largeur, hauteur));
        if (!joueur.getInventory().add(photo)) {
            joueur.drop(photo, false);
        }
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1F, 0.8F);
        joueur.displayClientMessage(Component.literal("🖨 Photo imprimée en " + largeur + " × " + hauteur + " : clic droit sur un mur pour l'accrocher.")
                .withStyle(ChatFormatting.GREEN), true);
    }

    private Impression() {
    }
}
