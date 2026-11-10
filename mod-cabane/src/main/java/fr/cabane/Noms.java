package fr.cabane;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

/**
 * Les noms français pour les textes que le serveur écrit lui-même (« Apporter 16 × Morue cuite ») : un serveur dédié ne
 * connaît que l'anglais. La table data/cabane/noms_fr.json vient de mod-cabane/outils/noms-fr.mjs ; un nom absent reste
 * celui du jeu.
 */
public final class Noms {
    private static Map<String, String> table;

    private static Map<String, String> table() {
        if (table == null) {
            try (InputStream in = Noms.class.getResourceAsStream("/data/cabane/noms_fr.json")) {
                table = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() { }.getType());
            } catch (Exception e) {
                Cabane.LOGGER.error("Noms français illisibles", e);
                table = Map.of();
            }
        }
        return table;
    }

    public static String de(ItemStack objet) {
        if (objet.has(DataComponents.CUSTOM_NAME)) {
            return objet.getHoverName().getString();
        }
        String nom = table().get(objet.getDescriptionId());
        return nom != null ? nom : objet.getHoverName().getString();
    }

    public static String de(EntityType<?> type) {
        String nom = table().get(type.getDescriptionId());
        return nom != null ? nom : type.getDescription().getString();
    }

    /** Au démarrage : on charge la table tout de suite, et le journal dit si elle est bonne. */
    public static void charger() {
        Cabane.LOGGER.info("Noms français : {} (ex. {})", table().size(), de(new ItemStack(net.minecraft.world.item.Items.COOKED_COD)));
    }

    private Noms() {
    }
}
