package fr.cabane;

import com.mojang.logging.LogUtils;
import fr.cabane.bloc.Etals;
import fr.cabane.boutique.Boutique;
import fr.cabane.boutique.Contrats;
import fr.cabane.casino.Casino;
import fr.cabane.economie.Economie;
import fr.cabane.tcg.Cartes;
import fr.cabane.telephone.PhoneNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Mod maison du serveur « La Cabane » : téléphones (Simple Voice Chat), économie en SakaiDollars,
 * marché, étals, casino, cartes à collectionner, parachute, centre de map et reliques.
 */
@Mod(Cabane.MODID)
public class Cabane {
    public static final String MODID = "cabane";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Cabane(IEventBus modBus) {
        CabaneBlocks.register(modBus);
        CabaneItems.register(modBus);
        CabaneComponents.register(modBus);
        CabaneEntities.register(modBus);
        CabaneSons.register(modBus);
        fr.cabane.boisson.Boissons.register(modBus);
        fr.cabane.bouffe.FastFood.init();
        fr.cabane.drogue.MarcheNoir.register(modBus);
        fr.cabane.accessoire.Accessoires.register(modBus);
        fr.cabane.accessoire.Saiyan.register(modBus);
        fr.cabane.monde.CabaneMonde.register(modBus);
        fr.cabane.labo.Labo.register(modBus);
        fr.cabane.cartel.Cartel.register(modBus);
        modBus.addListener(PhoneNetwork::register);
        modBus.addListener(Economie::register);
        modBus.addListener(Boutique::register);
        modBus.addListener(Contrats::register);
        modBus.addListener(fr.cabane.boutique.Forge::register);
        fr.cabane.police.Police.register(modBus);
        modBus.addListener(Etals::register);
        modBus.addListener(Casino::register);
        modBus.addListener(Cartes::register);
        modBus.addListener(fr.cabane.telephone.Piaf::register);
        modBus.addListener(fr.cabane.telephone.Messages::register);
        modBus.addListener(fr.cabane.photo.Impression::register);
        modBus.addListener(fr.cabane.vegeta.EvenementVegeta::register);
        modBus.addListener(fr.cabane.pnj.Dialogues::register);
        modBus.addListener(fr.cabane.pnj.Missions::register);
        modBus.addListener(fr.cabane.bouffe.FastFood::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
