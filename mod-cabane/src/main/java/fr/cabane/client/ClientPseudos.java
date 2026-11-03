package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.accessoire.Masques;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * Les pseudos au-dessus des joueurs : on ne les voit que si on voit le joueur, jamais à travers un mur (une vitre ne
 * cache rien ; le mur est vérifié dans mixin/LivingEntityRendererMixin). Sous un masque ninja porté sur la tête
 * (accessoire/Masques), le pseudo devient « ? » ; à la ceinture, rien ne change.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientPseudos {
    private static final Component INCONNU = Component.literal("?");
    /** Les points du corps qu'on cherche à voir, en part de sa hauteur : la tête, le ventre, les pieds. */
    private static final double[] POINTS = {0.9, 0.5, 0.1};

    @SubscribeEvent
    public static void pseudo(RenderNameTagEvent event) {
        if (event.getEntity() instanceof Player joueur && Masques.surLaTete(joueur)) {
            event.setContent(INCONNU);
        }
    }

    /** Un bout de lui est-il visible d'ici ? Les blocs qu'on voit à travers (les vitres) ne comptent pas. */
    public static boolean enVue(Player joueur) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return true;
        }
        Vec3 oeil = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 pieds = joueur.getPosition(mc.getTimer().getGameTimeDeltaPartialTick(true));
        for (double part : POINTS) {
            Vec3 point = pieds.add(0, joueur.getBbHeight() * part, 0);
            ClipContext rayon = new ClipContext(oeil, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
            if (mc.level.clip(rayon).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private ClientPseudos() {
    }
}
