package fr.cabane;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Les sons du mod : la voiture de police (synthétisée en direct) et les voix des PNJ (fichiers de assets/cabane/sounds/voix). */
public final class CabaneSons {
    public static final DeferredRegister<SoundEvent> SONS = DeferredRegister.create(Registries.SOUND_EVENT, Cabane.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> VOITURE = son("voiture");
    /** Le bruit de chaque téléportation de Minato : un vrai coup de tonnerre (ceux de Minecraft), pas trop fort. */
    public static final DeferredHolder<SoundEvent, SoundEvent> MINATO_FLASH = son("minato.flash");

    /** Une voix par PNJ : quand on ouvre son dialogue, il dit une de ses répliques enregistrées (s'il en a). */
    public static final List<String> VOIX = List.of("gardien", "cartographe", "marchand", "forgeron", "fee", "pole_emploi", "cousin",
            "guerisseuse", "garde_ouest", "garde_est", "macron", "garde_president", "vegeta", "orque",
            "jean", "jacques", "francis", "louis", "bernard", "gerard", "ginette", "monique", "martine", "thierry", "conteur", "prisonnier");
    public static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> VOIX_PNJ = VOIX.stream()
            .collect(Collectors.toMap(Function.identity(), nom -> son("voix." + nom)));

    private static DeferredHolder<SoundEvent, SoundEvent> son(String nom) {
        return SONS.register(nom, () -> SoundEvent.createVariableRangeEvent(Cabane.id(nom)));
    }

    static void register(IEventBus modBus) {
        SONS.register(modBus);
    }

    private CabaneSons() {
    }
}
