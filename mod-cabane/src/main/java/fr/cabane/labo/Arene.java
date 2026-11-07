package fr.cabane.labo;

import fr.cabane.Cabane;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * La salle d'essai du labo (construite par LaboBuilder.arene) : un clic droit sur une arme d'un râtelier en donne une
 * (les armes restent accrochées) ; personne n'y meurt (à zéro, on tombe K.O. et on se relève) ; le bouton du pupitre
 * de la galerie lance un compte à rebours, que voient seulement ceux qui sont dans la salle.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Arene {
    /** Le compte à rebours, en ticks depuis l'appui sur le bouton ; -1 : rien en cours. */
    private static int compte = -1;

    /** Les armes d'un râtelier, du nord au sud : rien qui fasse très mal. */
    static ItemStack[] armes(RegistryAccess registres, boolean rouge) {
        ItemStack baton = new ItemStack(Items.STICK);
        baton.enchant(registres.registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.KNOCKBACK), 2);
        ItemStack bouclier = new ItemStack(Items.SHIELD);
        bouclier.set(DataComponents.BASE_COLOR, rouge ? DyeColor.RED : DyeColor.BLUE);
        return new ItemStack[] {new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.WOODEN_SHOVEL), baton, bouclier,
                new ItemStack(Items.SNOWBALL)};
    }

    /** Dans la salle d'essai : l'arène, l'entrée des sujets ou la galerie. */
    static boolean dedans(Entity e) {
        if (e.level().dimension() != Labo.DIMENSION) {
            return false;
        }
        BlockPos p = e.blockPosition();
        int y = p.getY() - LaboBuilder.SOL;
        return p.getX() > LaboBuilder.AX0 && p.getX() < LaboBuilder.AX1 && p.getZ() > LaboBuilder.AZ0 && p.getZ() < LaboBuilder.AZ1
                && y >= 0 && y < LaboBuilder.HAUT_AILE;
    }

    /** Un cadre d'un des deux râteliers (reconnu à sa place : côté client, on ne connaît pas les tags des entités). */
    private static boolean ratelier(Entity e) {
        if (!(e instanceof ItemFrame) || e.level().dimension() != Labo.DIMENSION) {
            return false;
        }
        BlockPos p = e.blockPosition();
        return (p.getX() == LaboBuilder.AX0 + 1 || p.getX() == LaboBuilder.AX1 - 1) && p.getY() == LaboBuilder.SOL + LaboBuilder.RATELIER_Y
                && p.getZ() >= LaboBuilder.RATELIER_Z0 && p.getZ() < LaboBuilder.RATELIER_Z0 + 5;
    }

    @SubscribeEvent
    public static void onClicRatelier(PlayerInteractEvent.EntityInteract event) {
        if (!ratelier(event.getTarget())) {
            return;
        }
        // l'arme reste au râtelier, et l'objet qu'on tient n'est pas utilisé (pas de boule de neige lancée dessus)
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer joueur)
                || !(event.getTarget() instanceof ItemFrame cadre) || cadre.getItem().isEmpty()) {
            return;
        }
        ItemStack modele = cadre.getItem();
        int voulu = modele.getMaxStackSize() > 1 ? 16 : 1;
        int deja = joueur.getInventory().countItem(modele.getItem());
        if (deja >= voulu) {
            joueur.displayClientMessage(Component.literal("Tu en as déjà.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        ItemStack arme = modele.copyWithCount(voulu - deja);
        if (!joueur.getInventory().add(arme)) {
            joueur.drop(arme, false);
        }
        joueur.level().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.3F);
    }

    /** À zéro dans la salle d'essai, on ne meurt pas : on tombe K.O., et on se relève. */
    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur) || !dedans(joueur)) {
            return;
        }
        event.setCanceled(true);
        joueur.setHealth(joueur.getMaxHealth());
        joueur.clearFire();
        joueur.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 4, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 2, false, false));
        Labo.titre(joueur, Component.literal("K.O.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal("tu te relèves… pour cette fois").withStyle(ChatFormatting.GRAY));
        ServerLevel level = joueur.serverLevel();
        level.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.7F, 0.6F);
        for (ServerPlayer autre : level.players()) {
            if (autre != joueur && dedans(autre)) {
                autre.displayClientMessage(Component.literal(joueur.getName().getString() + " est K.O. !").withStyle(ChatFormatting.RED), true);
            }
        }
    }

    @SubscribeEvent
    public static void onBouton(PlayerInteractEvent.RightClickBlock event) {
        if (compte < 0 && event.getLevel() instanceof ServerLevel level && level.dimension() == Labo.DIMENSION
                && event.getHand() == InteractionHand.MAIN_HAND && event.getPos().equals(LaboBuilder.abs(LaboBuilder.BOUTON_COMBAT.getX(),
                        LaboBuilder.BOUTON_COMBAT.getY(), LaboBuilder.BOUTON_COMBAT.getZ()))) {
            compte = 0;
        }
    }

    /** 3, 2, 1… COMBAT ! */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (compte < 0) {
            return;
        }
        ServerLevel level = Labo.niveau(event.getServer());
        if (level == null) {
            compte = -1;
            return;
        }
        if (compte % 20 == 0) {
            int n = 3 - compte / 20;
            Component titre = n > 0 ? Component.literal(String.valueOf(n)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    : Component.literal("COMBAT !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            double x = LaboBuilder.CX + 0.5, y = LaboBuilder.SOL + 3, z = LaboBuilder.CZ + 0.5;
            if (n > 0) {
                level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_PLING, SoundSource.BLOCKS, 2F, 0.8F);
            } else {
                level.playSound(null, x, y, z, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3F, 0.7F);
                level.playSound(null, x, y, z, SoundEvents.RAID_HORN, SoundSource.BLOCKS, 1.2F, 1F);
            }
            for (ServerPlayer joueur : level.players()) {
                if (dedans(joueur)) {
                    joueur.connection.send(new ClientboundSetTitlesAnimationPacket(0, n > 0 ? 18 : 30, n > 0 ? 2 : 12));
                    joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
                    joueur.connection.send(new ClientboundSetTitleTextPacket(titre));
                }
            }
        }
        if (++compte > 60) {
            compte = -1;
        }
    }

    private Arene() {
    }
}
