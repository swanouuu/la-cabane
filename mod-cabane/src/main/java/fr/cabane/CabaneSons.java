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
    /** L'éveil d'Ichibi : un rugissement par défaut, le son de la vidéo de l'hôte dans le pack privé (voix/effets/ichibi.eveil.ogg). */
    public static final DeferredHolder<SoundEvent, SoundEvent> ICHIBI_EVEIL = son("ichibi.eveil");
    /** La foudre : l'électricité statique qui crépite sur lui tant qu'il est éveillé, le choc d'un coup, la charge du Kirin. */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUDRE_STATIQUE = son("foudre.statique");
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUDRE_ZAP = son("foudre.zap");
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUDRE_CHARGE = son("foudre.charge");
    /** Il crie « Kirin » : un tonnerre par défaut, la voix de l'hôte dans le pack privé (voix/effets/foudre.kirin.ogg). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUDRE_KIRIN = son("foudre.kirin");

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
