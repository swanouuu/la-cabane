package fr.cabane.labo;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Ceux qui servent de cibles dans les salles d'entraînement du WOOHP : les robots du stand de tir, les mannequins de la
 * soufflerie, les cobayes du parfum et du chewing-gum, les intrus cachés dans le labyrinthe. Chacun a son poste : s'il
 * meurt, il revient quelques secondes plus tard ; un mannequin envoyé valser revient à sa place ; un robot abîmé se
 * répare quand on le laisse tranquille. Ils ne lâchent rien en mourant.
 */
@EventBusSubscriber(modid = Cabane.MODID)
final class WoohpEntrainement {
    static final String TAG = "woohp.entrainement";
    private static final String POSTE = "woohp.poste.";

    enum Genre {
        /** Immobile, il encaisse (le laser) et se répare. */
        ROBOT,
        /** Un porte-armure : il s'envole quand on souffle dessus, puis revient à sa place. */
        MANNEQUIN,
        /** Un animal qui se promène dans son enclos. */
        COBAYE,
        /** Immobile, caché dans le labyrinthe. */
        INTRUS
    }

    /** Un poste : qui, où (relatif au centre du QG, les pieds au sol), tourné vers où, et son enclos s'il en a un. */
    record Poste(int numero, Genre genre, EntityType<? extends LivingEntity> type, double x, double y, double z, float lacet, AABB enclos) {
        Vec3 position() {
            BlockPos o = WoohpTrajet.ORIGINE;
            return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
        }
    }

    /** Depuis combien de secondes un poste est vide, ou son mannequin hors de sa place. */
    private static final Map<Integer, Integer> ABSENTS = new HashMap<>();
    private static final Map<Integer, Integer> DEPLACES = new HashMap<>();

    static void creer(ServerLevel level, Poste p, String construction) {
        LivingEntity e = p.type().create(level);
        if (e == null) {
            return;
        }
        Vec3 pos = p.position();
        e.moveTo(pos.x, pos.y, pos.z, p.lacet(), 0F);
        e.setYHeadRot(p.lacet());
        e.setYBodyRot(p.lacet());
        e.addTag(WoohpBuilder.TAG);
        e.addTag(construction);
        e.addTag(TAG);
        e.addTag(POSTE + p.numero());
        if (e instanceof Mob m) {
            m.setPersistenceRequired();
            m.setNoAi(p.genre() == Genre.ROBOT || p.genre() == Genre.INTRUS);
        }
        if (e instanceof ArmorStand mannequin) {
            // une tenue d'entraînement bleu ciel
            mannequin.setShowArms(true);
            mannequin.setNoBasePlate(true);
            mannequin.setItemSlot(EquipmentSlot.HEAD, teint(Items.LEATHER_HELMET));
            mannequin.setItemSlot(EquipmentSlot.CHEST, teint(Items.LEATHER_CHESTPLATE));
            mannequin.setItemSlot(EquipmentSlot.LEGS, teint(Items.LEATHER_LEGGINGS));
            mannequin.setItemSlot(EquipmentSlot.FEET, teint(Items.LEATHER_BOOTS));
        }
        level.addFreshEntity(e);
    }

    private static ItemStack teint(net.minecraft.world.item.Item item) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(0x7FD4FF, false));
        return s;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        ServerLevel labo = Labo.niveau(server);
        if (labo == null || LaboData.get(server).versionWoohp() != WoohpBuilder.VERSION) {
            return;
        }
        boolean quelquun = false;
        for (ServerPlayer j : labo.players()) {
            quelquun |= Woohp.pres(j);
        }
        if (!quelquun) {
            return;
        }
        String construction = WoohpBuilder.tagConstruction(LaboData.get(server).constructionWoohp());
        Map<Integer, List<LivingEntity>> trouves = new HashMap<>();
        for (LivingEntity e : labo.getEntitiesOfClass(LivingEntity.class, WoohpBuilder.zoneSalles(), e -> e.getTags().contains(TAG))) {
            for (String t : e.getTags()) {
                if (t.startsWith(POSTE)) {
                    trouves.computeIfAbsent(Integer.parseInt(t.substring(POSTE.length())), k -> new ArrayList<>()).add(e);
                }
            }
        }
        for (Poste p : WoohpBuilder.postes()) {
            Vec3 chez = p.position();
            // tant que son coin n'est pas chargé, on ne peut pas savoir s'il est là
            if (!labo.isPositionEntityTicking(BlockPos.containing(chez))) {
                continue;
            }
            List<LivingEntity> l = trouves.getOrDefault(p.numero(), List.of());
            LivingEntity e = l.stream().filter(LivingEntity::isAlive).findFirst().orElse(null);
            for (LivingEntity autre : l) {
                if (autre != e && autre.isAlive()) {
                    autre.discard();
                }
            }
            if (e == null) {
                // il revient au bout de 5 secondes, dans un petit nuage
                if (ABSENTS.merge(p.numero(), 1, Integer::sum) >= 5) {
                    ABSENTS.remove(p.numero());
                    creer(labo, p, construction);
                    nuage(labo, chez);
                }
                continue;
            }
            ABSENTS.remove(p.numero());
            if (e.getHealth() < e.getMaxHealth() && e.tickCount - e.getLastHurtByMobTimestamp() > 200) {
                e.setHealth(e.getMaxHealth());
            }
            double loin = e.position().distanceToSqr(chez);
            if (p.genre() == Genre.MANNEQUIN) {
                // posé ailleurs depuis 6 secondes : il retourne à sa place
                if (loin > 1 && e.getDeltaMovement().lengthSqr() < 0.01) {
                    if (DEPLACES.merge(p.numero(), 1, Integer::sum) >= 6) {
                        DEPLACES.remove(p.numero());
                        nuage(labo, e.position());
                        e.teleportTo(chez.x, chez.y, chez.z);
                        e.setDeltaMovement(Vec3.ZERO);
                        e.setYRot(p.lacet());
                        nuage(labo, chez);
                    }
                } else if (loin <= 1) {
                    DEPLACES.remove(p.numero());
                }
            } else if (p.enclos() != null && !p.enclos().contains(e.position())) {
                // sorti de son enclos (soufflé par-dessus la barrière) : on le ramène
                nuage(labo, e.position());
                e.teleportTo(chez.x, chez.y, chez.z);
                nuage(labo, chez);
            }
        }
    }

    private static void nuage(ServerLevel level, Vec3 p) {
        level.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.6, p.z, 12, 0.3, 0.4, 0.3, 0.02);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.6F, 1.6F);
    }

    /** Les cibles ne lâchent ni objets ni expérience. */
    @SubscribeEvent
    public static void butin(LivingDropsEvent event) {
        if (event.getEntity().getTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void experience(LivingExperienceDropEvent event) {
        if (event.getEntity().getTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    private WoohpEntrainement() {
    }
}
