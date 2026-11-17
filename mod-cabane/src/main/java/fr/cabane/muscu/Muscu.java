package fr.cabane.muscu;

import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneItems;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * La salle de muscu (voir docs/muscu) : des machines noires et rouges à poser (tapis, elliptique, vélo, rameur, tirages,
 * développé couché, biceps, presse, rack d'haltères) et des haltères de 5 à 30 kg. Sur une machine, une série de petits
 * QTE avec les touches de déplacement : raté = un cœur, trois ratés = on lâche ; série réussie = Force (muscu) ou Vitesse
 * (cardio), puis deux minutes de récup avant le prochain bonus. Le QTE tourne chez le joueur ; ici on compte, on blesse,
 * on récompense, et on prévient les joueurs autour pour qu'ils voient bouger la machine.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Muscu {
    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    private static final DeferredRegister<CreativeModeTab> ONGLETS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cabane.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<SiegeMuscu>> SIEGE = ENTITES.register("siege_muscu",
            () -> EntityType.Builder.<SiegeMuscu>of(SiegeMuscu::new, MobCategory.MISC).sized(0.4F, 0.4F).clientTrackingRange(10)
                    .updateInterval(20).noSave().build("siege_muscu"));

    public static final Map<Machine, DeferredBlock<MachineBlock>> BLOCS = new LinkedHashMap<>();
    public static final Map<Integer, DeferredItem<HaltereItem>> HALTERES = new LinkedHashMap<>();

    static {
        for (Machine m : Machine.values()) {
            BLOCS.put(m, CabaneBlocks.BLOCKS.registerBlock(m.id, p -> new MachineBlock(p, m),
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.5F, 6F).sound(SoundType.NETHERITE_BLOCK).noOcclusion()));
        }
        for (int kg : Machine.KILOS) {
            HALTERES.put(kg, CabaneItems.ITEMS.register("haltere_" + kg, () -> new HaltereItem(new Item.Properties().stacksTo(2), kg)));
        }
    }

    public static final List<DeferredItem<BlockItem>> OBJETS = BLOCS.values().stream().map(b -> CabaneItems.ITEMS.registerSimpleBlockItem(b)).toList();

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineBlockEntity>> MACHINE_BE = CabaneBlocks.BLOCK_ENTITIES.register("machine_muscu",
            () -> BlockEntityType.Builder.of(MachineBlockEntity::new, BLOCS.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    /** L'onglet du mode créatif : toutes les machines et les haltères, à poser. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ONGLET = ONGLETS.register("muscu", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cabane_muscu"))
            .icon(() -> new ItemStack(BLOCS.get(Machine.DEVELOPPE).get()))
            .displayItems((params, out) -> {
                OBJETS.forEach(o -> out.accept(o.get()));
                HALTERES.values().forEach(h -> out.accept(h.get()));
            })
            .build());

    public static void register(IEventBus modBus) {
        ENTITES.register(modBus);
        ONGLETS.register(modBus);
        modBus.addListener(Muscu::paquets);
    }

    // ------------------------------------------------------------------ les paquets

    /** Du joueur : DEBUT (kg = haltère, 0 = machine), REP réussie, RATE, ABANDON. */
    public record Action(byte action, int kg) implements CustomPacketPayload {
        public static final byte DEBUT = 0, REP = 1, RATE = 2, ABANDON = 3;
        public static final Type<Action> TYPE = new Type<>(Cabane.id("muscu_action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeByte(p.action());
            buf.writeVarInt(p.kg());
        }, buf -> new Action(buf.readByte(), buf.readVarInt()));

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /** Aux joueurs autour : le joueur {@code joueur} commence (kg) ou finit sa série, réussit ou rate une répétition. */
    public record Anim(int joueur, byte quoi, int kg) implements CustomPacketPayload {
        public static final byte FIN = 0, DEBUT = 1, REP = 2, RATE = 3;
        public static final Type<Anim> TYPE = new Type<>(Cabane.id("muscu_anim"));
        public static final StreamCodec<FriendlyByteBuf, Anim> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.joueur());
            buf.writeByte(p.quoi());
            buf.writeVarInt(p.kg());
        }, buf -> new Anim(buf.readVarInt(), buf.readByte(), buf.readVarInt()));

        @Override
        public Type<Anim> type() {
            return TYPE;
        }
    }

    /** Au joueur : sa série est finie (réussie ou non) et ce qu'il y a gagné. */
    public record Resultat(boolean ok, String titre, String texte) implements CustomPacketPayload {
        public static final Type<Resultat> TYPE = new Type<>(Cabane.id("muscu_resultat"));
        public static final StreamCodec<FriendlyByteBuf, Resultat> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.ok());
            buf.writeUtf(p.titre());
            buf.writeUtf(p.texte());
        }, buf -> new Resultat(buf.readBoolean(), buf.readUtf(), buf.readUtf()));

        @Override
        public Type<Resultat> type() {
            return TYPE;
        }
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer j) {
                action(j, p);
            }
        }));
        r.playToClient(Anim.TYPE, Anim.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.muscu.ClientMuscu.anim(p)));
        r.playToClient(Resultat.TYPE, Resultat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.muscu.ClientMuscu.resultat(p)));
    }

    // ------------------------------------------------------------------ les séries

    private static final class Serie {
        final Machine machine;
        final int kg;
        final int total;
        int reps;
        int rates;
        long dernier;

        Serie(Machine machine, int kg, int total) {
            this.machine = machine;
            this.kg = kg;
            this.total = total;
        }
    }

    private static final Map<UUID, Serie> SERIES = new HashMap<>();
    /** La fin de la récup (heure du monde) de chaque joueur : pas de nouveau bonus avant. */
    private static final Map<UUID, Long> RECUP = new HashMap<>();
    static final int RECUP_TICKS = 20 * 120;

    private static void action(ServerPlayer j, Action a) {
        long t = j.serverLevel().getGameTime();
        Serie s = SERIES.get(j.getUUID());
        switch (a.action()) {
            case Action.DEBUT -> {
                Serie nouvelle;
                if (a.kg() > 0) {
                    if (!(j.getMainHandItem().getItem() instanceof HaltereItem h) || h.kg != a.kg() || j.isPassenger()) {
                        return;
                    }
                    nouvelle = new Serie(null, h.kg, 8);
                } else {
                    if (!(j.getVehicle() instanceof SiegeMuscu siege) || siege.machine() == null) {
                        return;
                    }
                    nouvelle = new Serie(siege.machine(), 0, siege.machine().total);
                }
                nouvelle.dernier = t;
                SERIES.put(j.getUUID(), nouvelle);
                diffuser(j, Anim.DEBUT, nouvelle.kg);
            }
            case Action.REP -> {
                if (s == null) {
                    return;
                }
                // pas plus vite qu'une vraie répétition (le rythme du cardio, une demi-seconde en muscu)
                long mini = s.machine != null && s.machine.cardio() ? 4 : 8;
                if (t - s.dernier < mini) {
                    return;
                }
                s.dernier = t;
                s.reps++;
                diffuser(j, Anim.REP, s.kg);
                j.serverLevel().playSound(null, j.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.4F, 1.2F + 0.05F * s.reps);
                if (s.reps >= s.total) {
                    finir(j, s, true);
                }
            }
            case Action.RATE -> {
                if (s == null) {
                    return;
                }
                s.rates++;
                diffuser(j, Anim.RATE, s.kg);
                if (!j.isCreative() && !j.isSpectator()) {
                    j.invulnerableTime = 0;
                    j.hurt(j.damageSources().generic(), 2F);
                }
                if (s.rates >= 3 || j.isDeadOrDying()) {
                    finir(j, s, false);
                }
            }
            case Action.ABANDON -> {
                if (s != null) {
                    SERIES.remove(j.getUUID());
                    diffuser(j, Anim.FIN, s.kg);
                }
            }
            default -> {
            }
        }
    }

    private static void finir(ServerPlayer j, Serie s, boolean ok) {
        SERIES.remove(j.getUUID());
        diffuser(j, Anim.FIN, s.kg);
        if (!ok) {
            String titre = s.machine == Machine.TAPIS ? "Tu tombes du tapis" : "Série ratée";
            PacketDistributor.sendToPlayer(j, new Resultat(false, titre, "3 ratés : tu lâches. Pas de bonus."));
            if (s.machine != null) {
                j.stopRiding();
            }
            return;
        }
        long t = j.serverLevel().getGameTime();
        Long fin = RECUP.get(j.getUUID());
        if (fin != null && fin > t) {
            PacketDistributor.sendToPlayer(j, new Resultat(true, "Série réussie",
                    "Encore en récup : pas de nouveau bonus (" + (fin - t + 19) / 20 + " s)."));
            return;
        }
        Machine.Gain gain = s.machine != null ? s.machine.gain : Machine.Gain.FORCE;
        int niveau = s.machine != null ? s.machine.niveau : Machine.gainHaltere(s.kg)[0];
        int duree = s.machine != null ? s.machine.duree : Machine.gainHaltere(s.kg)[1];
        MobEffectInstance actuel = j.getEffect(gain.effet());
        if (actuel == null || actuel.getAmplifier() <= niveau - 1) {
            j.addEffect(new MobEffectInstance(gain.effet(), Math.max(duree * 20, actuel == null ? 0 : actuel.getDuration()), niveau - 1));
        }
        RECUP.put(j.getUUID(), t + RECUP_TICKS);
        j.serverLevel().playSound(null, j.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.3F);
        PacketDistributor.sendToPlayer(j, new Resultat(true, "Série réussie",
                gain.nom + " " + (niveau == 1 ? "I" : "II") + " pendant " + duree(duree) + ". Récup : 2 min avant le prochain bonus."));
    }

    /** Le joueur a quitté sa machine (ou lâché ses haltères) : la série s'arrête sans bonus. */
    static void annuler(ServerPlayer j) {
        Serie s = SERIES.remove(j.getUUID());
        if (s != null) {
            diffuser(j, Anim.FIN, s.kg);
        }
    }

    private static void diffuser(ServerPlayer j, byte quoi, int kg) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(j, new Anim(j.getId(), quoi, kg));
    }

    public static String duree(int secondes) {
        if (secondes < 60) {
            return secondes + " s";
        }
        return secondes / 60 + " min" + (secondes % 60 != 0 ? " " + secondes % 60 : "");
    }

    @SubscribeEvent
    public static void depart(PlayerEvent.PlayerLoggedOutEvent event) {
        SERIES.remove(event.getEntity().getUUID());
    }

    private Muscu() {
    }
}
