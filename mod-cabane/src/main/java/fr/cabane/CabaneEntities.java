package fr.cabane;

import fr.cabane.parachute.ParachuteEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CabaneEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<ParachuteEntity>> PARACHUTE = ENTITIES.register("parachute",
            () -> EntityType.Builder.<ParachuteEntity>of(ParachuteEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.6F).clientTrackingRange(10).updateInterval(3).build("parachute"));

    public static final DeferredHolder<EntityType<?>, EntityType<fr.cabane.police.VoitureEntity>> VOITURE_POLICE = ENTITIES.register("voiture_police",
            () -> EntityType.Builder.<fr.cabane.police.VoitureEntity>of(fr.cabane.police.VoitureEntity::new, MobCategory.MISC)
                    .sized(2.0F, 1.9F).clientTrackingRange(10).build("voiture_police"));

    public static final DeferredHolder<EntityType<?>, EntityType<fr.cabane.photo.TableauPhoto>> TABLEAU_PHOTO = ENTITIES.register("tableau_photo",
            () -> EntityType.Builder.<fr.cabane.photo.TableauPhoto>of(fr.cabane.photo.TableauPhoto::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE).build("tableau_photo"));

    public static final DeferredHolder<EntityType<?>, EntityType<fr.cabane.vegeta.CombattantZ>> COMBATTANT_Z = ENTITIES.register("combattant_z",
            () -> EntityType.Builder.<fr.cabane.vegeta.CombattantZ>of(fr.cabane.vegeta.CombattantZ::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.8F).clientTrackingRange(16).fireImmune().build("combattant_z"));

    public static final DeferredHolder<EntityType<?>, EntityType<fr.cabane.vegeta.KiBlast>> KI_BLAST = ENTITIES.register("ki_blast",
            () -> EntityType.Builder.<fr.cabane.vegeta.KiBlast>of(fr.cabane.vegeta.KiBlast::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F).clientTrackingRange(8).updateInterval(2).build("ki_blast"));

    /** Le Hachibi géant : sa boîte est petite (il ne touche rien), son dessin fait 76 blocs de haut. */
    public static final DeferredHolder<EntityType<?>, EntityType<fr.cabane.hachibi3.Hachibi3>> HACHIBI3 = ENTITIES.register("hachibi3",
            () -> EntityType.Builder.<fr.cabane.hachibi3.Hachibi3>of(fr.cabane.hachibi3.Hachibi3::new, MobCategory.MISC)
                    .sized(8F, 16F).clientTrackingRange(20).updateInterval(20).fireImmune().build("hachibi3"));

    static void register(IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(COMBATTANT_Z.get(), fr.cabane.vegeta.CombattantZ.attributs().build()));
        ENTITIES.register(modBus);
    }

    private CabaneEntities() {
    }
}
