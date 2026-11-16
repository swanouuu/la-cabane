package fr.cabane.pnj;

import fr.cabane.Cabane;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * « Le géant de fer » : la salle du Wroughtnaut est enfouie en pleine roche, introuvable au hasard. Brokk donne une
 * carte avec une croix rouge sur la salle la plus proche, à l'acceptation et quand on revient le voir sans elle.
 */
public final class CarteGeant {
    static final String MISSION = "brokk_geant";
    private static final String MARQUE = "cabane_carte_geant";
    /** La salle du Wroughtnaut (Mowzie's Mobs), listée dans data/cabane/tags/worldgen/structure/salle_du_geant.json. */
    private static final TagKey<Structure> SALLE = TagKey.create(Registries.STRUCTURE, Cabane.id("salle_du_geant"));

    /** Pour la mission du géant de fer : la carte, si le joueur ne l'a pas déjà sur lui. */
    static void donner(ServerPlayer joueur, Missions.Mission m) {
        if (m.id().equals(MISSION) && !aLaCarte(joueur)) {
            donner(joueur);
        }
    }

    /** Donne une carte vers la salle du géant la plus proche. Vrai si une salle a été trouvée. */
    public static boolean donner(ServerPlayer joueur) {
        ServerLevel monde = joueur.server.getLevel(Level.OVERWORLD);
        if (monde == null) {
            return false;
        }
        // depuis le joueur s'il est dans le monde normal, sinon depuis le point d'apparition
        BlockPos depart = joueur.level() == monde ? joueur.blockPosition() : monde.getSharedSpawnPos();
        BlockPos salle = monde.findNearestMapStructure(SALLE, depart, 100, false);
        if (salle == null) {
            joueur.displayClientMessage(Component.literal("Brokk ne sait pas où dort le géant. Cherche sous la terre.").withStyle(ChatFormatting.GRAY), false);
            return false;
        }
        ItemStack carte = MapItem.create(monde, salle.getX(), salle.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(monde, carte);
        MapItemSavedData.addTargetDecoration(carte, salle, "geant", MapDecorationTypes.RED_X);
        carte.set(DataComponents.ITEM_NAME, Component.literal("Carte du géant de fer"));
        carte.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("La croix rouge : sa salle, sous la terre.").withStyle(ChatFormatting.GRAY),
                Component.literal("Creuse entre la couche 20 et la couche 50.").withStyle(ChatFormatting.GRAY),
                Component.literal("Vers X " + salle.getX() + ", Z " + salle.getZ()).withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARQUE, true);
        CustomData.set(DataComponents.CUSTOM_DATA, carte, tag);
        Missions.donner(joueur, carte);
        joueur.displayClientMessage(Component.literal("Brokk te donne la carte du géant de fer.").withStyle(ChatFormatting.GOLD), false);
        return true;
    }

    private static boolean aLaCarte(ServerPlayer joueur) {
        for (int i = 0; i < joueur.getInventory().getContainerSize(); i++) {
            CustomData d = joueur.getInventory().getItem(i).get(DataComponents.CUSTOM_DATA);
            if (d != null && d.copyTag().getBoolean(MARQUE)) {
                return true;
            }
        }
        return false;
    }

    private CarteGeant() {
    }
}
