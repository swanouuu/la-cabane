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

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    private CabaneEntities() {
    }
}
