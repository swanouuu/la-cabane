package fr.cabane.arene;

import fr.cabane.lames.Lames;
import fr.cabane.pouvoir.Kaguya;
import fr.cabane.pouvoir.Vent;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Le stuff de l'arène. En entrant au combat, tout ce qu'on porte est mis de côté (sauvegardé tout de suite, même si le jeu
 * plante) sauf les lames de la Brume et les armes que donne l'éveil ; tout le monde reçoit alors le même kit. En sortant,
 * le kit disparaît et chacun retrouve son stuff, à la même place.
 *
 * <p>Le kit : armure en fer complète Protection II, épée en fer, bouclier, arc Puissance I et 32 flèches, 3 pommes dorées,
 * 16 steaks. Rien ne s'use (les combats durent assez longtemps pour ça).
 */
final class Kit {
    private static final String MARQUE = "cabane_arene_kit";

    /** Ce qu'on garde sur soi au combat : les 7 lames de la Brume, la hallebarde de vent, l'épée d'os de Kaguya. */
    static boolean garde(ItemStack s) {
        return !s.isEmpty() && (Lames.sorte(s) != null || s.is(Vent.hallebarde()) || s.is(Kaguya.epee()));
    }

    static boolean duKit(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(MARQUE);
    }

    /** Met de côté tout ce qui n'est pas gardé (s'il y avait déjà un rangement, il est d'abord rendu). */
    static void ranger(ServerPlayer joueur) {
        AreneData data = AreneData.get(joueur.server);
        if (data.rangement(joueur.getUUID()) != null) {
            rendre(joueur);
        }
        HolderLookup.Provider reg = joueur.registryAccess();
        Inventory inv = joueur.getInventory();
        ListTag liste = new ListTag();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || garde(s)) {
                continue;
            }
            CompoundTag t = new CompoundTag();
            t.putInt("Slot", i);
            t.put("item", s.save(reg));
            liste.add(t);
            inv.setItem(i, ItemStack.EMPTY);
        }
        ItemStack curseur = joueur.containerMenu.getCarried();
        if (!curseur.isEmpty() && !garde(curseur)) {
            CompoundTag t = new CompoundTag();
            t.putInt("Slot", -1);
            t.put("item", curseur.save(reg));
            liste.add(t);
            joueur.containerMenu.setCarried(ItemStack.EMPTY);
        }
        CompoundTag r = new CompoundTag();
        r.put("objets", liste);
        data.setRangement(joueur.getUUID(), r);
        // tout de suite sur le disque : si le serveur plante en plein combat, le stuff est là au redémarrage
        joueur.server.overworld().getDataStorage().save();
    }

    /** Enlève le kit et rend ce qui était rangé, à sa place (ou dans le sac, ou à ses pieds s'il est plein). */
    static void rendre(ServerPlayer joueur) {
        retirerKit(joueur);
        AreneData data = AreneData.get(joueur.server);
        CompoundTag r = data.rangement(joueur.getUUID());
        if (r == null) {
            return;
        }
        HolderLookup.Provider reg = joueur.registryAccess();
        Inventory inv = joueur.getInventory();
        ListTag liste = r.getList("objets", Tag.TAG_COMPOUND);
        java.util.List<ItemStack> reste = new java.util.ArrayList<>();
        for (int k = 0; k < liste.size(); k++) {
            CompoundTag t = liste.getCompound(k);
            ItemStack s = ItemStack.parseOptional(reg, t.getCompound("item"));
            if (s.isEmpty()) {
                continue;
            }
            int slot = t.getInt("Slot");
            if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) {
                inv.setItem(slot, s);
            } else {
                reste.add(s);
            }
        }
        for (ItemStack s : reste) {
            if (!inv.add(s)) {
                joueur.drop(s, false);
            }
        }
        data.setRangement(joueur.getUUID(), null);
        joueur.server.overworld().getDataStorage().save();
        joueur.inventoryMenu.broadcastChanges();
    }

    /** Enlève tout objet du kit (sac, armure, main gauche, curseur). */
    static void retirerKit(ServerPlayer joueur) {
        Inventory inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (duKit(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (duKit(joueur.containerMenu.getCarried())) {
            joueur.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    /** Donne le kit (en remplaçant un kit déjà là : flèches, pommes et steaks reviennent au complet). */
    static void donner(ServerPlayer joueur) {
        retirerKit(joueur);
        HolderLookup.Provider reg = joueur.registryAccess();
        Inventory inv = joueur.getInventory();
        armure(inv, 39, kit(enchante(new ItemStack(Items.IRON_HELMET), reg, Enchantments.PROTECTION, 2)), joueur);
        armure(inv, 38, kit(enchante(new ItemStack(Items.IRON_CHESTPLATE), reg, Enchantments.PROTECTION, 2)), joueur);
        armure(inv, 37, kit(enchante(new ItemStack(Items.IRON_LEGGINGS), reg, Enchantments.PROTECTION, 2)), joueur);
        armure(inv, 36, kit(enchante(new ItemStack(Items.IRON_BOOTS), reg, Enchantments.PROTECTION, 2)), joueur);
        armure(inv, 40, kit(new ItemStack(Items.SHIELD)), joueur);
        ajouter(joueur, kit(new ItemStack(Items.IRON_SWORD)));
        ajouter(joueur, kit(enchante(new ItemStack(Items.BOW), reg, Enchantments.POWER, 1)));
        ajouter(joueur, kit(new ItemStack(Items.GOLDEN_APPLE, 3)));
        ajouter(joueur, kit(new ItemStack(Items.COOKED_BEEF, 16)));
        ajouter(joueur, kit(new ItemStack(Items.ARROW, 32)));
        joueur.inventoryMenu.broadcastChanges();
    }

    /** Un emplacement d'armure (36 à 39) ou la main gauche (40) : ce qui l'occupe (une arme gardée) va dans le sac. */
    private static void armure(Inventory inv, int slot, ItemStack s, ServerPlayer joueur) {
        ItemStack la = inv.getItem(slot);
        if (!la.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
            if (!inv.add(la)) {
                joueur.drop(la, false);
            }
        }
        inv.setItem(slot, s);
    }

    private static void ajouter(ServerPlayer joueur, ItemStack s) {
        if (!joueur.getInventory().add(s)) {
            joueur.drop(s, false);
        }
    }

    private static ItemStack kit(ItemStack s) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> t.putBoolean(MARQUE, true));
        if (s.isDamageableItem()) {
            s.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        }
        return s;
    }

    private static ItemStack enchante(ItemStack s, HolderLookup.Provider reg, ResourceKey<Enchantment> e, int niveau) {
        Holder<Enchantment> h = reg.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(e);
        s.enchant(h, niveau);
        return s;
    }

    private Kit() {
    }
}
