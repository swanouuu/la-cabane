package fr.cabane.client.donjon;

import fr.cabane.Cabane;
import fr.cabane.donjon.Cristaux;
import fr.cabane.donjon.Donjon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Dans un donjon, un clic gauche sur un cristal de mana le frappe (le serveur compte les coups et le détache). */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCristaux {
    @SubscribeEvent
    public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level.dimension() != Donjon.DIMENSION) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult b) || b.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockState s = mc.level.getBlockState(b.getBlockPos());
        if (s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD) || s.is(Blocks.MEDIUM_AMETHYST_BUD)) {
            PacketDistributor.sendToServer(new Cristaux.Miner(b.getBlockPos()));
            event.setSwingHand(true);
        }
    }

    private ClientCristaux() {
    }
}
