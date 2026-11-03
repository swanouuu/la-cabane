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
 * Le pack privé « Beauvais - voix » (les vraies voix, le vrai son du WOOHP, Kurama…) : s'il est dans le dossier
 * resourcepacks, il est toujours actif et cloué tout en haut (on ne peut ni le décocher ni passer un autre pack
 * au-dessus). Il prend la place du pack que le jeu trouve lui-même dans ce dossier (le même nom) : on n'en voit qu'un
 * dans la liste des packs de ressources. Il ne contient que des choses du mod (cabane:…) : les packs de textures des
 * joueurs (blocs, objets…) s'appliquent quand même.
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
        for (String fichier : new String[] {NOM, NOM + ".zip"}) {
            Path p = dossier.resolve(fichier);
            if (!Files.exists(p)) {
                continue;
            }
            Pack.ResourcesSupplier contenu = Files.isDirectory(p) ? new PathPackResources.PathResourcesSupplier(p) : new FilePackResources.FileResourcesSupplier(p);
            // le même identifiant que celui du dossier resourcepacks : notre pack (ajouté après) remplace le sien
            PackLocationInfo info = new PackLocationInfo("file/" + fichier, Component.literal(NOM), PackSource.BUILT_IN, Optional.empty());
            Pack pack = Pack.readMetaAndCreate(info, contenu, PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, true));
            if (pack != null) {
                event.addRepositorySource(ajouter -> ajouter.accept(pack));
                return;
            }
        }
    }

    private PackVoix() {
    }
}
