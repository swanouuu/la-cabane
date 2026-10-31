package fr.cabane.bloc;

import fr.cabane.Cabane;
import fr.cabane.client.ClientEtal;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Réseau et règles des étals : vitrine pour les clients, achat avec confirmation, protection. */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Etals {

    public record Lot(int index, ItemStack stack, long prix) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Lot> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Lot::index,
                ItemStack.STREAM_CODEC, Lot::stack,
                ByteBufCodecs.VAR_LONG, Lot::prix,
                Lot::new);
    }

    /** Serveur → client : la vitrine d'un étal. */
    public record Vitrine(BlockPos pos, String vendeur, List<Lot> lots) implements CustomPacketPayload {
        public static final Type<Vitrine> TYPE = new Type<>(Cabane.id("etal_vitrine"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Vitrine> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Vitrine::pos,
                ByteBufCodecs.STRING_UTF8, Vitrine::vendeur,
                Lot.CODEC.apply(ByteBufCodecs.list()), Vitrine::lots,
                Vitrine::new);

        @Override
        public Type<Vitrine> type() {
            return TYPE;
        }
    }

    /** Client → serveur : acheter le lot (le prix vu à l'écran est renvoyé pour éviter les surprises). */
    public record Acheter(BlockPos pos, int index, long prixVu) implements CustomPacketPayload {
        public static final Type<Acheter> TYPE = new Type<>(Cabane.id("etal_acheter"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Acheter> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Acheter::pos,
                ByteBufCodecs.VAR_INT, Acheter::index,
                ByteBufCodecs.VAR_LONG, Acheter::prixVu,
                Acheter::new);

        @Override
        public Type<Acheter> type() {
            return TYPE;
        }
    }

    /** Client → serveur : le propriétaire change ses prix. */
    public record Prix(BlockPos pos, List<Long> prix) implements CustomPacketPayload {
        public static final Type<Prix> TYPE = new Type<>(Cabane.id("etal_prix"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Prix> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Prix::pos,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(EtalBlockEntity.LOTS)), Prix::prix,
                Prix::new);

        @Override
        public Type<Prix> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Vitrine.TYPE, Vitrine.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientEtal.vitrine(p)))
                .playToServer(Acheter.TYPE, Acheter.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        acheter(joueur, p);
                    }
                }))
                .playToServer(Prix.TYPE, Prix.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && proche(joueur, p.pos())
                            && joueur.level().getBlockEntity(p.pos()) instanceof EtalBlockEntity etal && etal.estProprietaire(joueur.getUUID())) {
                        etal.definirPrix(p.prix().stream().mapToLong(Long::longValue).toArray());
                        joueur.displayClientMessage(Component.literal("Prix enregistrés.").withStyle(ChatFormatting.GREEN), true);
                    }
                }));
    }

    static void montrer(ServerPlayer joueur, EtalBlockEntity etal) {
        List<Lot> lots = new ArrayList<>();
        for (int i = 0; i < EtalBlockEntity.LOTS; i++) {
            ItemStack s = etal.stock().getStackInSlot(i);
            if (!s.isEmpty() && etal.prix(i) > 0) {
                lots.add(new Lot(i, s.copy(), etal.prix(i)));
            }
        }
        Economie.sync(joueur);
        PacketDistributor.sendToPlayer(joueur, new Vitrine(etal.getBlockPos(), etal.nomProprietaire(), lots));
    }

    private static boolean proche(ServerPlayer joueur, BlockPos pos) {
        return joueur.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64;
    }

    private static void acheter(ServerPlayer acheteur, Acheter demande) {
        if (!proche(acheteur, demande.pos()) || !(acheteur.level().getBlockEntity(demande.pos()) instanceof EtalBlockEntity etal)) {
            return;
        }
        int i = demande.index();
        if (i < 0 || i >= EtalBlockEntity.LOTS) {
            return;
        }
        ItemStack lot = etal.stock().getStackInSlot(i);
        long prix = etal.prix(i);
        if (lot.isEmpty() || prix <= 0 || prix != demande.prixVu()) {
            acheteur.displayClientMessage(Component.literal("Ce lot a changé, regarde à nouveau.").withStyle(ChatFormatting.RED), true);
            montrer(acheteur, etal);
            return;
        }
        if (etal.estProprietaire(acheteur.getUUID())) {
            acheteur.displayClientMessage(Component.literal("C'est ton propre étal !").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!Economie.payer(acheteur, prix)) {
            return;
        }
        ItemStack achete = etal.stock().extractItem(i, lot.getCount(), false);
        Economie.crediter(acheteur.server, etal.proprietaire(), prix);
        if (!acheteur.getInventory().add(achete.copy())) {
            acheteur.drop(achete, false);
        }
        acheteur.displayClientMessage(Component.literal("Acheté : " + achete.getCount() + " × ").append(achete.getHoverName())
                .append(" pour ").append(Economie.texte(prix)).withStyle(ChatFormatting.GREEN), true);
        ServerPlayer vendeur = acheteur.server.getPlayerList().getPlayer(etal.proprietaire());
        if (vendeur != null) {
            vendeur.sendSystemMessage(Component.literal(acheteur.getGameProfile().getName() + " a acheté " + achete.getCount() + " × ")
                    .append(achete.getHoverName()).append(" à ton étal : +").append(Economie.texte(prix)).withStyle(ChatFormatting.GREEN));
        }
        montrer(acheteur, etal);
    }

    /** Seul le propriétaire (ou un admin) peut casser un étal. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof EtalBlockEntity etal
                && etal.proprietaire() != null
                && !etal.estProprietaire(event.getPlayer().getUUID())
                && !event.getPlayer().hasPermissions(2)) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal("Cet étal appartient à " + etal.nomProprietaire() + ".")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private Etals() {
    }
}
