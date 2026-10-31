package fr.cabane;

import com.mojang.logging.LogUtils;
import fr.cabane.telephone.PhoneNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Mod maison du serveur « La Cabane » : téléphones (Simple Voice Chat), centre de map
 * avec la cabane abandonnée, reliques cachées dans les donjons.
 */
@Mod(Cabane.MODID)
public class Cabane {
    public static final String MODID = "cabane";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Cabane(IEventBus modBus) {
        CabaneItems.register(modBus);
        modBus.addListener(PhoneNetwork::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
