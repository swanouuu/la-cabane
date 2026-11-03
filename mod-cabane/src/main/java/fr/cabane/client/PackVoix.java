package fr.cabane.client;

import fr.cabane.Cabane;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Le pack de sons privé « Beauvais - voix » (les vraies voix, le vrai son du WOOHP…) : s'il est dans le dossier
 * resourcepacks, il est toujours actif, tout en haut, même si personne ne l'a coché dans les packs de ressources.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class PackVoix {
    private static final String NOM = "Beauvais - voix";

    @SubscribeEvent
    public static void trouver(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path dossier = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
        for (Path p : new Path[] {dossier.resolve(NOM), dossier.resolve(NOM + ".zip")}) {
            if (!Files.exists(p)) {
                continue;
            }
            Pack.ResourcesSupplier contenu = Files.isDirectory(p) ? new PathPackResources.PathResourcesSupplier(p) : new FilePackResources.FileResourcesSupplier(p);
            PackLocationInfo info = new PackLocationInfo("cabane_voix", Component.literal(NOM), PackSource.BUILT_IN, Optional.empty());
            Pack pack = Pack.readMetaAndCreate(info, contenu, PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
            if (pack != null) {
                event.addRepositorySource(ajouter -> ajouter.accept(pack));
                return;
            }
        }
    }

    private PackVoix() {
    }
}
