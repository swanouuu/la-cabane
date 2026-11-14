package fr.cabane.donjon;

import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les cristaux de mana : dans chaque donjon, des grappes d'améthyste posées sur un socle d'améthyste. On est en mode
 * aventure (rien ne se casse), alors c'est le mod qui les mine : quelques coups (clic gauche) et le cristal tombe dans
 * le sac. En sortant du donjon, le Système rachète tous ceux qu'on ramène ({@link #PRIX} pièces pièce). Ils repoussent
 * pour le groupe suivant.
 */
public final class Cristaux {
    /** Ce que le Système paie pour un cristal. */
    public static final long PRIX = 5;
    /** Les coups qu'il faut pour détacher un cristal. */
    private static final int COUPS = 3;

    /** Client → serveur : je frappe ce cristal. */
    public record Miner(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Miner> TYPE = new Type<>(Cabane.id("donjon_miner"));
        public static final StreamCodec<ByteBuf, Miner> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Miner::pos, Miner::new);

        @Override
        public Type<Miner> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(Miner.TYPE, Miner.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer j) {
                    frapper(j, p.pos());
                }
            }));
        }
    }

    /** L'objet : un éclat violet qui luit. */
    public static class CristalItem extends Item {
        public CristalItem(Properties p) {
            super(p);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lignes, TooltipFlag flag) {
            lignes.add(Component.literal("Miné dans un donjon de rang S.").withStyle(ChatFormatting.LIGHT_PURPLE));
            lignes.add(Component.literal("Le Système le rachète " + Economie.format(PRIX) + " à la sortie du donjon.").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public boolean isFoil(ItemStack stack) {
            return true;
        }
    }

    public static final net.neoforged.neoforge.registries.DeferredItem<CristalItem> CRISTAL = fr.cabane.CabaneItems.CRISTAL_MANA;

    /** Un cristal qui se mine : une grappe (ou un gros bourgeon) d'améthyste sur un bloc d'améthyste. */
    public static boolean minable(BlockState s, BlockState dessous) {
        return (s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD) || s.is(Blocks.MEDIUM_AMETHYST_BUD)) && dessous.is(Blocks.AMETHYST_BLOCK);
    }

    private record Mine(BlockPos pos, BlockState etat) {
    }

    private static final Map<TypeDonjon, List<Mine>> MINES = new EnumMap<>(TypeDonjon.class);
    private static final Map<BlockPos, Integer> COUPS_DONNES = new HashMap<>();

    private static void frapper(ServerPlayer j, BlockPos pos) {
        if (!Donjon.dedans(j) || j.isSpectator() || j.position().distanceToSqr(pos.getCenter()) > 6 * 6) {
            return;
        }
        ServerLevel l = j.serverLevel();
        BlockState s = l.getBlockState(pos);
        if (!(s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD) || s.is(Blocks.MEDIUM_AMETHYST_BUD))) {
            return;
        }
        if (!minable(s, l.getBlockState(pos.below()))) {
            j.displayClientMessage(Component.literal("Ce cristal est pris dans la roche. Cherchez ceux qui poussent sur l'améthyste.")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int n = COUPS_DONNES.merge(pos.immutable(), 1, Integer::sum);
        j.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        l.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.1);
        if (n < COUPS) {
            l.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 1F, 0.8F + n * 0.15F);
            l.destroyBlockProgress(j.getId(), pos, n * 10 / COUPS);
            return;
        }
        COUPS_DONNES.remove(pos);
        l.destroyBlockProgress(j.getId(), pos, -1);
        MINES.computeIfAbsent(TypeDonjon.de(pos.getX()), t -> new ArrayList<>()).add(new Mine(pos.immutable(), s));
        l.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        l.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 1.2F, 1F);
        l.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.5F, 1.3F);
        l.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.25, 0.25, 0.25, 0.05);
        ItemStack c = new ItemStack(CRISTAL.get());
        if (!j.getInventory().add(c)) {
            j.drop(c, false);
        }
        int total = compter(j);
        j.displayClientMessage(Component.literal("Cristal de mana +1   (" + total + " dans le sac)").withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    private static int compter(ServerPlayer j) {
        int n = 0;
        for (ItemStack s : j.getInventory().items) {
            if (s.is(CRISTAL.get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Un nouveau groupe : les cristaux minés par les précédents repoussent. */
    static void restaurer(ServerLevel l, TypeDonjon type) {
        List<Mine> m = MINES.remove(type);
        if (m == null) {
            return;
        }
        for (Mine x : m) {
            if (l.getBlockState(x.pos()).isAir()) {
                l.setBlock(x.pos(), x.etat(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** En sortant du donjon : le Système rachète les cristaux de ce joueur. */
    static void vendre(ServerPlayer j) {
        int n = 0;
        for (ItemStack s : j.getInventory().items) {
            if (s.is(CRISTAL.get())) {
                n += s.getCount();
                s.setCount(0);
            }
        }
        if (n == 0) {
            return;
        }
        long gain = n * PRIX;
        Economie.crediter(j.server, j.getUUID(), gain);
        j.sendSystemMessage(Component.literal("[SYSTÈME] " + n + (n > 1 ? " cristaux de mana rachetés : +" : " cristal de mana racheté : +") + Economie.format(gain))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    private Cristaux() {
    }
}
