package fr.cabane.labo;

import fr.cabane.Cabane;
import fr.cabane.pouvoir.Espionne;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Le bouton des gadgets du QG, au bout du tapis roulant : un appui, et toute la panoplie (les quatre gadgets, les
 * lunettes, les bottes, le grappin et les trois tenues) se pose le long du tapis, à prendre. Pas plus d'une fois toutes
 * les 3 secondes.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class WoohpGadgets {
    private static final int ATTENTE = 60;
    private static long prochain;

    @SubscribeEvent
    public static void onClic(PlayerInteractEvent.RightClickBlock event) {
        BlockPos b = WoohpBuilder.BOUTON_GADGETS;
        if (!(event.getEntity() instanceof ServerPlayer j) || !Labo.dedans(j) || !event.getPos().equals(WoohpBuilder.abs(b.getX(), b.getY(), b.getZ()))) {
            return;
        }
        ServerLevel labo = j.serverLevel();
        long maintenant = labo.getGameTime();
        if (maintenant < prochain) {
            return;
        }
        prochain = maintenant + ATTENTE;
        List<ItemStack> panoplie = Espionne.panoplie();
        // posés de gauche à droite sur le tapis, qui les emmène jusqu'à la butée
        for (int i = 0; i < panoplie.size(); i++) {
            double x = WoohpBuilder.TAPIS_X0 + 0.5 + i * (WoohpBuilder.TAPIS_X1 - WoohpBuilder.TAPIS_X0) / (double) panoplie.size();
            Vec3 p = Vec3.atLowerCornerOf(WoohpBuilder.abs(0, 2, WoohpBuilder.TAPIS_Z)).add(x, 0.05, 0.5);
            ItemEntity objet = new ItemEntity(labo, p.x, p.y, p.z, panoplie.get(i), 0, 0.1, 0);
            objet.setDefaultPickUpDelay();
            labo.addFreshEntity(objet);
            labo.sendParticles(ParticleTypes.WAX_ON, p.x, p.y + 0.3, p.z, 4, 0.2, 0.2, 0.2, 0.5);
        }
        BlockPos depart = WoohpBuilder.abs(WoohpBuilder.TAPIS_X0 - 1, 1, WoohpBuilder.TAPIS_Z);
        labo.playSound(null, depart, SoundEvents.DISPENSER_DISPENSE, SoundSource.BLOCKS, 1F, 1.2F);
        labo.playSound(null, depart, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1F, 1.5F);
        labo.playSound(null, depart, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.2F, 1.3F);
    }

    private WoohpGadgets() {
    }
}
