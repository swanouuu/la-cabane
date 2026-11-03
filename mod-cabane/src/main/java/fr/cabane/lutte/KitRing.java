package fr.cabane.lutte;

import fr.cabane.lutte.RingBlocs.Couleur;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/**
 * Le kit de ring : clic droit au sol pour le premier coin, clic droit pour le deuxième (le contour s'affiche en
 * pointillés), un troisième clic monte le ring, de 5 × 5 à 40 × 40, carré ou rectangle, n'importe où. Accroupi + clic
 * droit : la couleur des cordes. Casser un poteau range le ring dans un kit.
 */
public class KitRing extends Item {
    /** Les coins posés par chacun, et jusqu'à quand on les garde (tick du serveur). */
    private record Selection(BlockPos a, BlockPos b, int fin) {
    }

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    public KitRing(Properties p) {
        super(p);
    }

    public static Couleur couleur(ItemStack kit) {
        return Couleur.values()[Math.floorMod(kit.getOrDefault(Lutte.KIT_COULEUR.get(), 0), Couleur.values().length)];
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
        ItemStack kit = joueur.getItemInHand(main);
        if (joueur.isShiftKeyDown()) {
            if (!level.isClientSide) {
                changerCouleur(joueur, kit);
            }
            return InteractionResultHolder.sidedSuccess(kit, level.isClientSide);
        }
        return InteractionResultHolder.pass(kit);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player joueur = ctx.getPlayer();
        if (joueur == null) {
            return InteractionResult.PASS;
        }
        if (ctx.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ServerPlayer j = (ServerPlayer) joueur;
        ItemStack kit = ctx.getItemInHand();
        if (j.isShiftKeyDown()) {
            changerCouleur(j, kit);
            return InteractionResult.SUCCESS;
        }
        ServerLevel level = j.serverLevel();
        int maintenant = level.getServer().getTickCount();
        BlockPos clic = ctx.getClickedPos();
        Selection s = SELECTIONS.get(j.getUUID());
        if (s != null && s.fin() < maintenant) {
            s = null;
        }
        if (s == null) {
            SELECTIONS.put(j.getUUID(), new Selection(clic, null, maintenant + 1200));
            dire(j, "Premier coin posé. Clic droit sur le deuxième coin.", ChatFormatting.YELLOW);
            level.playSound(null, clic, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1F, 1.2F);
            return InteractionResult.SUCCESS;
        }
        if (s.b() == null) {
            int l = Math.abs(clic.getX() - s.a().getX()) + 1, p = Math.abs(clic.getZ() - s.a().getZ()) + 1;
            if (l < Rings.MIN || p < Rings.MIN || l > Rings.MAX || p > Rings.MAX) {
                dire(j, "Le ring doit faire entre " + Rings.MIN + " et " + Rings.MAX + " blocs de côté (là : " + l + " × " + p + ").", ChatFormatting.RED);
                return InteractionResult.SUCCESS;
            }
            SELECTIONS.put(j.getUUID(), new Selection(s.a(), clic, maintenant + 1200));
            dire(j, "Ring de " + l + " × " + p + " : clic droit encore pour le monter.", ChatFormatting.YELLOW);
            level.playSound(null, clic, SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1F, 1.4F);
            return InteractionResult.SUCCESS;
        }
        // le troisième clic : on monte le ring sur le premier coin
        int x0 = Math.min(s.a().getX(), s.b().getX()), z0 = Math.min(s.a().getZ(), s.b().getZ());
        int l = Math.abs(s.b().getX() - s.a().getX()) + 1, p = Math.abs(s.b().getZ() - s.a().getZ()) + 1, y0 = s.a().getY() + 1;
        if (!Rings.place(level, x0, y0, z0, l, p)) {
            dire(j, "Il faut de la place : rien sur le sol, sur 4 blocs de haut.", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
        }
        SELECTIONS.remove(j.getUUID());
        Rings.construire(level, x0, y0, z0, l, p, couleur(kit), false);
        level.playSound(null, x0 + l / 2.0, y0 + 2, z0 + p / 2.0, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.6F, 1.3F);
        level.playSound(null, x0 + l / 2.0, y0 + 2, z0 + p / 2.0, Lutte.SON_CLOCHE.get(), SoundSource.BLOCKS, 1F, 1F);
        if (!j.getAbilities().instabuild) {
            kit.shrink(1);
        }
        dire(j, "Le ring est monté ! Accroupi pour passer entre les cordes.", ChatFormatting.GOLD);
        return InteractionResult.SUCCESS;
    }

    private static void changerCouleur(Player joueur, ItemStack kit) {
        Couleur c = Couleur.values()[(couleur(kit).ordinal() + 1) % Couleur.values().length];
        kit.set(Lutte.KIT_COULEUR.get(), c.ordinal());
        if (joueur instanceof ServerPlayer j) {
            dire(j, "Cordes " + c.nom(), ChatFormatting.AQUA);
        }
    }

    private static void dire(ServerPlayer j, String texte, ChatFormatting couleur) {
        j.displayClientMessage(Component.literal(texte).withStyle(couleur), true);
    }

    /** Toutes les demi-secondes : les coins et le contour en pointillés, pour celui qui tient le kit (lui seul les voit). */
    static void montrer(ServerPlayer j, int maintenant) {
        Selection s = SELECTIONS.get(j.getUUID());
        if (s == null) {
            return;
        }
        if (s.fin() < maintenant || !(j.getMainHandItem().getItem() instanceof KitRing || j.getOffhandItem().getItem() instanceof KitRing)) {
            if (s.fin() < maintenant) {
                SELECTIONS.remove(j.getUUID());
            }
            return;
        }
        ServerLevel level = j.serverLevel();
        DustParticleOptions jaune = new DustParticleOptions(new Vector3f(1F, 0.85F, 0.3F), 1.2F);
        for (BlockPos c : s.b() == null ? List.of(s.a()) : List.of(s.a(), s.b())) {
            level.sendParticles(j, ParticleTypes.END_ROD, true, c.getX() + 0.5, c.getY() + 1.4, c.getZ() + 0.5, 3, 0.05, 0.3, 0.05, 0.01);
        }
        if (s.b() == null) {
            return;
        }
        double x0 = Math.min(s.a().getX(), s.b().getX()), z0 = Math.min(s.a().getZ(), s.b().getZ());
        double x1 = Math.max(s.a().getX(), s.b().getX()) + 1, z1 = Math.max(s.a().getZ(), s.b().getZ()) + 1, y = s.a().getY() + 1.1;
        for (double x = x0; x <= x1; x += 0.5) {
            level.sendParticles(j, jaune, true, x, y, z0, 1, 0, 0, 0, 0);
            level.sendParticles(j, jaune, true, x, y, z1, 1, 0, 0, 0, 0);
        }
        for (double z = z0; z <= z1; z += 0.5) {
            level.sendParticles(j, jaune, true, x0, y, z, 1, 0, 0, 0, 0);
            level.sendParticles(j, jaune, true, x1, y, z, 1, 0, 0, 0, 0);
        }
    }

    static void oublier(UUID joueur) {
        SELECTIONS.remove(joueur);
    }

    @Override
    public void appendHoverText(ItemStack kit, TooltipContext ctx, List<Component> lignes, TooltipFlag options) {
        lignes.add(Component.literal("Cordes " + couleur(kit).nom()).withStyle(ChatFormatting.AQUA));
        lignes.add(Component.literal("Clic droit au sol : 1er coin, puis 2e coin, puis encore une fois pour le monter").withStyle(ChatFormatting.GRAY));
        lignes.add(Component.literal("De " + Rings.MIN + " × " + Rings.MIN + " à " + Rings.MAX + " × " + Rings.MAX).withStyle(ChatFormatting.GRAY));
        lignes.add(Component.keybind("key.sneak").append(" + ").append(Component.keybind("key.use")).append(" : la couleur des cordes").withStyle(ChatFormatting.GRAY));
        lignes.add(Component.literal("Casser un poteau range le ring").withStyle(ChatFormatting.GRAY));
    }
}
