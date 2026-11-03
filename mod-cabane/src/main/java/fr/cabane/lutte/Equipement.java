package fr.cabane.lutte;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Les 5 tenues de catch (à la place du plastron : on est torse nu, et gonflé) et les 5 masques (sur la tête, chacun avec
 * sa prise de finition et sa provoc). Elles ne protègent que dans un ring ou à la salle : dehors, ce sont des habits.
 */
public final class Equipement {
    public static final String[] TENUES = {"slip_noir", "slip_etoile", "short_luchador", "jean", "collant_flammes"};
    public static final String[] MASQUES = {"mysterio", "vipere", "champion", "demon", "taureau"};
    private static final String[] PRISES = {"le 619", "le RKO", "le AA", "le chokeslam", "le spear"};
    private static final String[] PROVOCS = {"le signe 619", "il tape le tapis", "tu me vois pas", "le feu aux poteaux", "il gratte le sol"};
    /** L'armure du catcheur, dans un ring : la tenue (14, solidité 4) et le masque (4). */
    public static final float ARMURE_TENUE = 14, SOLIDITE_TENUE = 4, ARMURE_MASQUE = 4;

    /** La tenue qu'il porte (0 à 4), ou -1. */
    public static int tenue(LivingEntity e) {
        return e.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof Tenue t ? t.index : -1;
    }

    /** Le masque qu'il porte (0 à 4), ou -1. */
    public static int masque(LivingEntity e) {
        return e.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof Masque m ? m.index : -1;
    }

    /** Une tenue : un clic droit l'enfile à la place du plastron. */
    public static final class Tenue extends Item implements Equipable {
        final int index;

        public Tenue(int index) {
            super(new Item.Properties().stacksTo(1));
            this.index = index;
        }

        @Override
        public EquipmentSlot getEquipmentSlot() {
            return EquipmentSlot.CHEST;
        }

        @Override
        public Holder<SoundEvent> getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            return swapWithEquipmentSlot(this, level, joueur, main);
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext ctx, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.literal("Torse nu et gonflé, tant que tu l'as sur toi").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal("Dans un ring : l'armure du catcheur, et tes coups font mal").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.keybind("key.attack").append(" : coup de poing ; en sautant : coup de pied ; en courant : corde à linge")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Un masque : un clic droit le met sur la tête. */
    public static final class Masque extends Item implements Equipable {
        final int index;

        public Masque(int index) {
            super(new Item.Properties().stacksTo(1));
            this.index = index;
        }

        @Override
        public EquipmentSlot getEquipmentSlot() {
            return EquipmentSlot.HEAD;
        }

        @Override
        public Holder<SoundEvent> getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            return swapWithEquipmentSlot(this, level, joueur, main);
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext ctx, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.literal("Prise : " + PRISES[index]).withStyle(ChatFormatting.GOLD));
            lignes.add(Component.literal("Provoc : " + PROVOCS[index]).withStyle(ChatFormatting.YELLOW));
            lignes.add(Component.keybind("key.use").append(" sur un joueur tout près : la prise (une toutes les 5 s)").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.keybind("key.sneak").append(" + ").append(Component.keybind("key.use")).append(" : la provoc").withStyle(ChatFormatting.GRAY));
            lignes.add(Component.literal(index == 0 ? "Le 619 : il faut qu'il soit dans les cordes" : "Dans un ring ou à la salle").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private Equipement() {
    }
}
