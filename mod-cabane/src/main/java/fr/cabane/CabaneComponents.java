package fr.cabane;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Les données attachées aux objets : quelle carte, le contenu d'un classeur. */
public final class CabaneComponents {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Cabane.MODID);

    /** L'identifiant de la carte, ex. « cabane:gardien ». */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> CARTE =
            COMPONENTS.registerComponentType("carte", b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** Les cartes rangées dans un classeur : identifiant → nombre. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Map<String, Integer>>> CLASSEUR =
            COMPONENTS.registerComponentType("classeur", b -> b
                    .persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
                    .networkSynchronized(ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT)));

    /** Le numéro gravé dans un téléphone (sa carte SIM), choisi une seule fois. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> NUMERO =
            COMPONENTS.registerComponentType("numero", b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** La chicha à laquelle un tuyau est accroché. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.core.BlockPos>> CHICHA =
            COMPONENTS.registerComponentType("chicha", b -> b.persistent(net.minecraft.core.BlockPos.CODEC)
                    .networkSynchronized(net.minecraft.core.BlockPos.STREAM_CODEC));

    /** Le goût d'une puff ou d'un tuyau de chicha (tiré au hasard). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> GOUT =
            COMPONENTS.registerComponentType("gout", b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** Une photo imprimée : laquelle, et sa taille sur le mur. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<fr.cabane.photo.PhotoImprimee>> PHOTO =
            COMPONENTS.registerComponentType("photo", b -> b.persistent(fr.cabane.photo.PhotoImprimee.CODEC)
                    .networkSynchronized(fr.cabane.photo.PhotoImprimee.STREAM_CODEC));

    static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private CabaneComponents() {
    }
}
