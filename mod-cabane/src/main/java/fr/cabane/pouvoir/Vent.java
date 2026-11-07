package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientVent;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le pouvoir Vent, d'après les planches de docs/vent (dans l'esprit de Yuno et de sa fée du vent). Trois listes
 * blanches : « vent » (le niveau 1), « vent2 » (la touche Éveil passe au niveau 1, puis au 2) et « vent3 » (jusqu'au
 * niveau 3, équilibré comme un niveau 4, voir {@link Vent3}). À tous les niveaux : pas de dégâts de chute.
 * <ul>
 * <li>niveau 1 : il garde son skin, avec un col de fourrure bleu nuit, et sa fée du vent tourne autour de lui. +20 % de
 * vitesse, protégé comme par du fer. Clic gauche : toute arme +2 et un petit souffle qui repousse. Clic droit : 3 tranches
 * de vent en éventail (25 blocs, elles traversent tout, 5 chacune, 5 s). Accroupi : un tourbillon file 15 blocs, attrape
 * le premier qu'il croise et le soulève à 6 blocs (4 + la chute, 15 s) ;</li>
 * <li>niveau 2 : il a absorbé la fée (uniforme, cape, couronne, ailes). +40 %, deux fois du fer, 4 sauts (3 en l'air, un
 * anneau de vent sous les pieds). Clic gauche : +4. Clic droit : 5 tranches plus grosses (7 chacune, 5 s). Clic droit
 * tenu 1 s : le faucon de vent (40 blocs, traverse tout, 12 et repousse, 15 s). Accroupi : la répulsion céleste, bras en
 * T, une sphère de vent de 10 blocs (12, tout le monde vole loin, 20 s).</li>
 * </ul>
 * Ce qu'on voit est dessiné par {@link ClientVent} ; rien ne casse.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Vent {
    public static final String NOM = "vent";
    public static final String NOM2 = "vent2";
    public static final String NOM3 = "vent3";

    public static final int RECHARGE_TRANCHES = 5 * 20;
    public static final int RECHARGE_TOURBILLON = 15 * 20;
    public static final int RECHARGE_REPULSION = 20 * 20;
    public static final int RECHARGE_FAUCON = 15 * 20;
    /** Le clic droit tenu 1 s : le faucon (niveau 2), la tempête (niveau 3). */
    public static final int TENU = 20;
    /** Au niveau 2 : 3 sauts en l'air. */
    public static final int SAUTS_EN_L_AIR = 3;
    /** La répulsion : le vent se ramasse (bras en T) avant d'éclater. */
    public static final int RAMASSE = 10;

    public static final double PORTEE_TRANCHES = 25;
    public static final double VITESSE_TRANCHES = 1.6;
    private static final float DEGATS_TRANCHE = 5;
    private static final float DEGATS_TRANCHE_2 = 7;
    private static final double PORTEE_TOURBILLON = 15;
    private static final double VITESSE_TOURBILLON = 0.75;
    private static final double HAUT_TOURBILLON = 6;
    private static final float DEGATS_TOURBILLON = 4;
    /** Le tourbillon soulève sa prise (en ticks), la garde en haut, puis la lâche. */
    private static final int MONTEE = 18;
    private static final int EN_HAUT = 8;
    private static final double RAYON_REPULSION = 10;
    private static final float DEGATS_REPULSION = 12;
    public static final double PORTEE_FAUCON = 40;
    /** Le faucon vole moins vite qu'une tranche : on a le temps de le diriger du regard (40 blocs en 1,25 s). */
    public static final double VITESSE_FAUCON = 1.6;
    private static final float DEGATS_FAUCON = 12;
    private static final float BONUS = 2;
    private static final float BONUS_2 = 4;
    /** La hallebarde du niveau 3 : 13 par coup (7 comme une épée en diamant, +6 du pouvoir). */
    private static final float DEGATS_HALLEBARDE = 13;
    private static final double[] VITESSE = {0, 0.2, 0.4, 0.6};
    /** En vol (niveau 3) : environ une fois et demie la vitesse de course (8,5 blocs/s), moins que le vol normal. */
    static final float VOL = 0.039F;

    private static final ResourceLocation VITE = Cabane.id("vent_vitesse");

    /**
     * La hallebarde de vent : au niveau 3, elle arrive dans sa main quand les armes se forment (ce qu'il tenait est rangé,
     * et rendu quand il s'endort). Sans recette ni onglet ; elle n'appartient qu'à l'esprit du vent (ailleurs, elle disparaît).
     */
    public static final ResourceLocation HALLEBARDE = Cabane.id("hallebarde_vent");

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        event.register(Registries.ITEM, HALLEBARDE, Hallebarde::new);
    }

    public static Item hallebarde() {
        return BuiltInRegistries.ITEM.get(HALLEBARDE);
    }

    /** Lente comme une hache (1,2 coup par seconde) ; ses dégâts viennent du pouvoir (voir degats). */
    private static final class Hallebarde extends Item {
        Hallebarde() {
            super(new Item.Properties().stacksTo(1).attributes(ItemAttributeModifiers.builder().add(Attributes.ATTACK_SPEED,
                    new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.8, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build()));
        }

        /** Chez quelqu'un qui n'est pas l'esprit du vent (endormi, ou qui l'a récupérée), elle s'efface. */
        @Override
        public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean tenue) {
            if (!level.isClientSide && entity instanceof ServerPlayer p && niveau(p) < 3) {
                stack.setCount(0);
            }
        }

        /** Lâchée par terre, elle se dissipe dans le vent. */
        @Override
        public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entite) {
            if (!entite.level().isClientSide) {
                entite.discard();
            }
            return true;
        }
    }

    /** Il tient la hallebarde en main (le clic gauche et le clic droit du niveau 3 ne marchent qu'avec elle). */
    static boolean tient(ServerPlayer joueur) {
        return joueur.getMainHandItem().is(hallebarde());
    }

    /** Ce qu'il tenait quand la hallebarde est arrivée dans sa main ; quand elle doit arriver (ticks du serveur). */
    private static final Map<UUID, ItemStack> RANGES = new HashMap<>();
    private static final Map<UUID, Integer> ARRIVEE = new HashMap<>();
    /** Les armes se forment 2,2 s après le début de l'éveil 3. */
    private static final int FORME = 44;

    private static boolean aLaHallebarde(ServerPlayer joueur) {
        Inventory inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(hallebarde())) {
                return true;
            }
        }
        return false;
    }

    /** La hallebarde arrive dans sa main (ce qu'il tenait est rangé de côté) ; perdue, elle revient dans son sac. */
    private static void donnerHallebarde(ServerPlayer joueur) {
        if (aLaHallebarde(joueur)) {
            return;
        }
        Inventory inv = joueur.getInventory();
        if (!RANGES.containsKey(joueur.getUUID())) {
            // ce qu'il tenait va dans son sac (rien n'est perdu s'il quitte en plein éveil) ; sac plein : gardé de côté
            ItemStack avant = inv.getItem(inv.selected).copy();
            inv.setItem(inv.selected, new ItemStack(hallebarde()));
            RANGES.put(joueur.getUUID(), avant.isEmpty() || inv.add(avant) ? ItemStack.EMPTY : avant);
        } else if (!inv.add(new ItemStack(hallebarde()))) {
            return;
        }
        son(joueur.serverLevel(), joueur.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F, 1.2F);
    }

    /** La hallebarde revient dans sa main : la barre d'objets se cale dessus, ou elle revient du sac à la place de ce qu'il tient. */
    private static void garderEnMain(ServerPlayer joueur) {
        Inventory inv = joueur.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).is(hallebarde())) {
                continue;
            }
            if (i < 9) {
                inv.selected = i;
                joueur.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(i));
            } else {
                ItemStack h = inv.items.get(i);
                inv.items.set(i, inv.items.get(inv.selected));
                inv.items.set(inv.selected, h);
            }
            return;
        }
    }

    /** Il s'endort : la hallebarde s'efface, et il retrouve ce qu'il tenait. */
    private static void reprendreHallebarde(ServerPlayer joueur) {
        ARRIVEE.remove(joueur.getUUID());
        Inventory inv = joueur.getInventory();
        int place = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(hallebarde())) {
                inv.setItem(i, ItemStack.EMPTY);
                if (place < 0) {
                    place = i;
                }
            }
        }
        if (joueur.containerMenu.getCarried().is(hallebarde())) {
            joueur.containerMenu.setCarried(ItemStack.EMPTY);
        }
        ItemStack avant = RANGES.remove(joueur.getUUID());
        if (avant != null && !avant.isEmpty()) {
            if (place >= 0 && inv.getItem(place).isEmpty()) {
                inv.setItem(place, avant);
            } else if (!inv.add(avant)) {
                joueur.drop(avant, false);
            }
        }
    }

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    /** Qui est éveillé (et à quel niveau), et quand chaque attaque sera prête (ticks du serveur). */
    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Map<UUID, Integer> PRET_DROIT = new HashMap<>();
    private static final Map<UUID, Integer> PRET_ACCROUPI = new HashMap<>();
    private static final Map<UUID, Integer> PRET_FAUCON = new HashMap<>();
    /** Combien de sauts en l'air il lui reste (niveau 2). */
    private static final Map<UUID, Integer> SAUTS = new HashMap<>();
    /**
     * Endormi (peut-être en l'air : en vol au niveau 3, après la tornade royale, entre deux sauts) : pas de dégâts de
     * chute avant d'être retombé au sol. Le tick de la fin de l'éveil, et s'il a quitté le sol depuis (1) ou non (0).
     */
    private static final Map<UUID, int[]> SANS_CHUTE = new HashMap<>();
    /** Le temps de nos propres coups : la frappe de l'arme ne doit pas les compter deux fois. */
    private static boolean dansPouvoir;

    // ================================================================ réseau

    public static final int TRANCHES = 0, FAUCON = 1, ACCROUPI = 2, SAUT = 3, LANCES = 4, TEMPETE = 5, LAME = 6;

    /** Client → serveur : une touche du Vent (les clics, s'accroupir, sauter en l'air). */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("vent_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Ce que montre un {@link Effet}. */
    public static final int E_TRANCHES = 0, E_TOURBILLON = 1, E_REPULSION = 2, E_FAUCON = 3, E_SAUT = 4, E_LAME = 5, E_TEMPETE = 6,
            E_PIQUE = 7, E_ULTI = 8, E_SOUFFLE = 9;

    /**
     * Serveur → ceux qui sont autour : un coup de vent de ce joueur. {@code sorte} dit lequel, {@code phase} où il en est,
     * {@code pos} et {@code dir} où et vers où, {@code n} un nombre (combien de tranches, le rayon, l'entité tenue…).
     */
    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("vent_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VAR_INT, Effet::phase,
                ByteBufCodecs.VECTOR3F, Effet::pos, ByteBufCodecs.VECTOR3F, Effet::dir, ByteBufCodecs.VAR_INT, Effet::n, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : où sont ses quatre lances (position puis direction, pour chacune ; vide : rentrées). */
    public record Lances(UUID joueur, List<Vector3f> points) implements CustomPacketPayload {
        public static final Type<Lances> TYPE = new Type<>(Cabane.id("vent_lances"));
        public static final StreamCodec<ByteBuf, Lances> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Lances::joueur, ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), Lances::points, Lances::new);

        @Override
        public Type<Lances> type() {
            return TYPE;
        }
    }

    /** Serveur → tous : ceux qui ont le pouvoir Vent (éveillés ou non) : leur fée tourne toujours autour d'eux. */
    public record Porteurs(List<UUID> joueurs) implements CustomPacketPayload {
        public static final Type<Porteurs> TYPE = new Type<>(Cabane.id("vent_porteurs"));
        public static final StreamCodec<ByteBuf, Porteurs> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Porteurs::joueurs, Porteurs::new);

        @Override
        public Type<Porteurs> type() {
            return TYPE;
        }
    }

    private static List<UUID> porteurs = List.of();

    private static List<UUID> porteurs(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().filter(p -> est(Pouvoirs.pouvoir(p))).map(Entity::getUUID).toList();
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            redire(joueur.server);
        }
    }

    /** Redit tout de suite à tous qui a le pouvoir (l'élu du vent : sa fée tourne sans attendre la seconde suivante). */
    static void redire(MinecraftServer server) {
        porteurs = porteurs(server);
        PacketDistributor.sendToAllPlayers(new Porteurs(porteurs));
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientVent.effet(p)))
                .playToClient(Lances.TYPE, Lances.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientVent.lances(p)))
                .playToClient(Porteurs.TYPE, Porteurs.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientVent.porteurs(p)));
    }

    /** À tous ceux qui sont à moins de 160 blocs de ce point. */
    static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3 autour) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, v(pos), v(dir), n), pos);
    }

    static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    /** Un coup du pouvoir : il compte même juste après un autre (les tranches, les lances arrivent ensemble). */
    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats) {
        dansPouvoir = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), degats);
        } finally {
            dansPouvoir = false;
        }
    }

    /** Ce qu'un coup de vent peut toucher. */
    static boolean touchable(ServerPlayer joueur, LivingEntity e) {
        return Pouvoirs.cible(joueur, e) && !e.isSpectator();
    }

    /** Le point de la droite de A à B le plus près de p, et à quelle distance : pour ce que traverse un coup de vent. */
    static double distanceAuSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        double l2 = d.lengthSqr();
        double u = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(d) / l2));
        return a.add(d.scale(u)).distanceTo(p);
    }

    /** Le milieu d'une entité. */
    static Vec3 milieu(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    // ================================================================ s'éveiller, s'endormir

    /** Il s'éveille ou monte d'un niveau : il va plus vite ; au niveau 3, la tornade le soulève et il vole. */
    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        vitesse(joueur, niveau);
        SAUTS.put(joueur.getUUID(), SAUTS_EN_L_AIR);
        ServerLevel monde = joueur.serverLevel();
        son(monde, joueur.position(), SoundEvents.BREEZE_WHIRL, 1.4F, niveau >= 3 ? 0.6F : niveau >= 2 ? 0.8F : 1.1F);
        son(monde, joueur.position(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), niveau >= 2 ? 1.6F : 0.8F, niveau >= 3 ? 0.6F : 0.9F);
        if (niveau >= 2) {
            son(monde, joueur.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.5F, 1.4F);
        }
        if (niveau >= 3) {
            ARRIVEE.put(joueur.getUUID(), joueur.server.getTickCount() + FORME);
            // la tornade verte le soulève : il reste en l'air (il vole)
            voler(joueur, true);
            joueur.setDeltaMovement(0, 0.95, 0);
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            son(monde, joueur.position(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 2F, 0.5F);
        }
    }

    static void endormir(ServerPlayer joueur) {
        Integer etait = EVEILLES.remove(joueur.getUUID());
        if (etait != null && joueur.isAlive()) {
            // une rafale : tout s'envole en feuilles (et la fée s'en va)
            son(joueur.serverLevel(), joueur.position(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1F, 1.3F);
            joueur.serverLevel().sendParticles(ParticleTypes.GUST, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 2, 0.4, 0.4, 0.4, 0);
        }
        if (etait != null && etait >= 3) {
            voler(joueur, false);
        }
        if (etait != null) {
            SANS_CHUTE.put(joueur.getUUID(), new int[]{joueur.server.getTickCount(), joueur.onGround() ? 0 : 1});
        }
        Vent3.endormir(joueur);
        reprendreHallebarde(joueur);
        TOURBILLONS.remove(joueur.getUUID());
        REPULSIONS.remove(joueur.getUUID());
        Faucon f = FAUCONS.remove(joueur.getUUID());
        if (f != null) {
            envoyer(f.monde, new Effet(joueur.getUUID(), E_FAUCON, 2, v(f.pos), v(f.dir), 0), f.pos);
        }
        vitesse(joueur, 0);
        PRET_DROIT.remove(joueur.getUUID());
        PRET_ACCROUPI.remove(joueur.getUUID());
        PRET_FAUCON.remove(joueur.getUUID());
        SAUTS.remove(joueur.getUUID());
    }

    /** Au niveau 3, il peut voler (le double saut, comme en créatif) ; endormi, il redescend doucement. */
    static void voler(ServerPlayer joueur, boolean oui) {
        if (joueur.isCreative() || joueur.isSpectator()) {
            return;
        }
        Abilities a = joueur.getAbilities();
        a.mayfly = oui;
        a.flying = oui;
        a.setFlyingSpeed(oui ? VOL : 0.05F);
        joueur.onUpdateAbilities();
        joueur.fallDistance = 0;
    }

    private static void vitesse(ServerPlayer joueur, int niveau) {
        AttributeInstance a = joueur.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) {
            return;
        }
        a.removeModifier(VITE);
        if (niveau > 0) {
            a.addTransientModifier(new AttributeModifier(VITE, VITESSE[Math.min(3, niveau)], AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    static boolean eveille(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    /** Son niveau en ce moment : 0 endormi, 1, 2, 3. */
    static int niveau(ServerPlayer joueur) {
        return eveille(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    static boolean pret(Map<UUID, Integer> recharges, ServerPlayer joueur) {
        return joueur.server.getTickCount() >= recharges.getOrDefault(joueur.getUUID(), 0);
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        int niveau = niveau(joueur);
        if (niveau == 0 || Vent3.ultiActif(joueur)) {
            return;
        }
        if (niveau >= 3) {
            switch (action) {
                case LANCES -> Vent3.lances(joueur);
                case TEMPETE -> Vent3.tempete(joueur);
                case ACCROUPI -> Vent3.repulsion(joueur);
                case LAME -> Vent3.lame(joueur);
                default -> { }
            }
            return;
        }
        switch (action) {
            case TRANCHES -> tranches(joueur, niveau);
            case FAUCON -> {
                if (niveau >= 2) {
                    faucon(joueur);
                }
            }
            case ACCROUPI -> {
                if (niveau >= 2) {
                    repulsion(joueur, RAYON_REPULSION, DEGATS_REPULSION, true, true);
                } else {
                    tourbillon(joueur);
                }
            }
            case SAUT -> sauter(joueur, niveau);
            default -> { }
        }
    }

    // ================================================================ les tranches de vent (et le faucon)

    /** Un coup de vent qui file tout droit et traverse tout : d'où, vers où, depuis quand, ce qu'il a déjà touché. */
    private static final class Lancer {
        final UUID lanceur;
        final ServerLevel monde;
        final Vec3 depart;
        final Vec3 dir;
        final int debut;
        final double vitesse;
        final double portee;
        final double rayon;
        final float degats;
        final double recul;
        final Set<Integer> touches = new HashSet<>();

        Lancer(UUID lanceur, ServerLevel monde, Vec3 depart, Vec3 dir, int debut, double vitesse, double portee, double rayon, float degats, double recul) {
            this.lanceur = lanceur;
            this.monde = monde;
            this.depart = depart;
            this.dir = dir;
            this.debut = debut;
            this.vitesse = vitesse;
            this.portee = portee;
            this.rayon = rayon;
            this.degats = degats;
            this.recul = recul;
        }
    }

    private static final List<Lancer> LANCERS = new ArrayList<>();

    /** Tourne d'un angle (en degrés) autour de la verticale. */
    static Vec3 tourner(Vec3 d, double degres) {
        double a = Math.toRadians(degres), c = Math.cos(a), s = Math.sin(a);
        return new Vec3(d.x * c - d.z * s, d.y, d.x * s + d.z * c);
    }

    /** Clic droit : 3 tranches en éventail (niveau 1), ou 5 plus grosses (niveau 2). */
    private static void tranches(ServerPlayer joueur, int niveau) {
        if (!pret(PRET_DROIT, joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_DROIT.put(joueur.getUUID(), maintenant + RECHARGE_TRANCHES);
        boolean fort = niveau >= 2;
        int n = fort ? 5 : 3;
        double ecart = fort ? 15 : 18;
        Vec3 regard = joueur.getLookAngle();
        Vec3 depart = joueur.getEyePosition().add(0, -0.35, 0).add(regard.scale(0.6));
        for (int i = 0; i < n; i++) {
            Vec3 dir = tourner(regard, (i - (n - 1) / 2.0) * ecart).normalize();
            LANCERS.add(new Lancer(joueur.getUUID(), joueur.serverLevel(), depart, dir, maintenant, VITESSE_TRANCHES, PORTEE_TRANCHES,
                    fort ? 1.7 : 1.3, fort ? DEGATS_TRANCHE_2 : DEGATS_TRANCHE, 0.4));
        }
        effet(joueur, E_TRANCHES, niveau, depart, regard, n);
        ServerLevel monde = joueur.serverLevel();
        son(monde, depart, SoundEvents.BREEZE_SHOOT, 1.4F, fort ? 0.8F : 1.1F);
        son(monde, depart, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, fort ? 0.7F : 1F);
    }

    /** Un faucon en vol : où il est, vers où, depuis quand, la distance faite, ce qu'il a déjà touché. */
    private static final class Faucon {
        final ServerLevel monde;
        Vec3 pos;
        Vec3 dir;
        double parcouru;
        final Set<Integer> touches = new HashSet<>();

        Faucon(ServerLevel monde, Vec3 pos, Vec3 dir) {
            this.monde = monde;
            this.pos = pos;
            this.dir = dir;
        }
    }

    private static final Map<UUID, Faucon> FAUCONS = new HashMap<>();

    /**
     * Clic droit tenu 1 s (niveau 2) : le faucon de vent part devant lui ; il le dirige du regard. Il traverse tout,
     * 40 blocs : 12 et il repousse chacun qu'il touche.
     */
    private static void faucon(ServerPlayer joueur) {
        if (!pret(PRET_FAUCON, joueur) || FAUCONS.containsKey(joueur.getUUID())) {
            return;
        }
        PRET_FAUCON.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_FAUCON);
        Vec3 regard = joueur.getLookAngle();
        Vec3 depart = joueur.getEyePosition().add(0, -0.3, 0).add(regard.scale(1));
        FAUCONS.put(joueur.getUUID(), new Faucon(joueur.serverLevel(), depart, regard));
        effet(joueur, E_FAUCON, 0, depart, regard, 0);
        ServerLevel monde = joueur.serverLevel();
        son(monde, depart, SoundEvents.PHANTOM_SWOOP, 2F, 1.3F);
        son(monde, depart, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.5F, 0.7F);
        monde.playSound(null, depart.x, depart.y, depart.z, SoundEvents.PARROT_IMITATE_BREEZE, SoundSource.PLAYERS, 2F, 0.6F);
    }

    /** Vers où il regarde, vu de p : le point qu'il vise (le bloc, ou à 60 blocs devant). */
    static Vec3 vise(ServerPlayer joueur, Vec3 p) {
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(60));
        HitResult bloc = joueur.serverLevel().clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 d = (bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation()).subtract(p);
        return d.lengthSqr() < 1e-4 ? joueur.getLookAngle() : d.normalize();
    }

    /** Un tick du faucon : il tourne vers où le joueur regarde, file, et frappe ce qu'il traverse. */
    private static boolean voler(ServerPlayer joueur, Faucon f) {
        f.dir = f.dir.add(vise(joueur, f.pos).subtract(f.dir).scale(0.28)).normalize();
        Vec3 a = f.pos;
        f.pos = f.pos.add(f.dir.scale(VITESSE_FAUCON));
        f.parcouru += VITESSE_FAUCON;
        for (LivingEntity e : f.monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, f.pos).inflate(3.2),
                e -> touchable(joueur, e) && !f.touches.contains(e.getId()))) {
            if (distanceAuSegment(milieu(e), a, f.pos) > 2.2 + e.getBbWidth() * 0.5) {
                continue;
            }
            f.touches.add(e.getId());
            blesser(joueur, e, DEGATS_FAUCON);
            e.setDeltaMovement(e.getDeltaMovement().add(f.dir.x * 1.4, 0.5, f.dir.z * 1.4));
            e.hurtMarked = true;
            son(f.monde, e.position(), SoundEvents.BREEZE_DEFLECT, 1.4F, 0.9F);
        }
        boolean fini = f.parcouru >= PORTEE_FAUCON;
        envoyer(f.monde, new Effet(joueur.getUUID(), E_FAUCON, fini ? 2 : 1, v(f.pos), v(f.dir), 0), f.pos);
        if (fini) {
            son(f.monde, f.pos, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.6F, 1.2F);
        } else if (f.parcouru % 8 < VITESSE_FAUCON) {
            son(f.monde, f.pos, SoundEvents.PHANTOM_FLAP, 1.6F, 1.4F);
        }
        return fini;
    }

    /** Un tick des coups de vent qui filent : tout ce qu'ils traversent prend (une fois chacun). */
    private static void lancers(MinecraftServer server, int maintenant) {
        LANCERS.removeIf(l -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(l.lanceur);
            if (joueur == null || joueur.level() != l.monde) {
                return true;
            }
            int t = maintenant - l.debut;
            double avant = Math.min(l.portee, t * l.vitesse), apres = Math.min(l.portee, (t + 1) * l.vitesse);
            Vec3 a = l.depart.add(l.dir.scale(avant)), b = l.depart.add(l.dir.scale(apres));
            for (LivingEntity e : l.monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(l.rayon + 1),
                    e -> touchable(joueur, e) && !l.touches.contains(e.getId()))) {
                if (distanceAuSegment(milieu(e), a, b) > l.rayon + e.getBbWidth() * 0.5) {
                    continue;
                }
                l.touches.add(e.getId());
                blesser(joueur, e, l.degats);
                e.setDeltaMovement(e.getDeltaMovement().add(l.dir.x * l.recul, 0.2 + 0.25 * l.recul, l.dir.z * l.recul));
                e.hurtMarked = true;
                son(l.monde, e.position(), SoundEvents.BREEZE_DEFLECT, 1F, 1.2F);
            }
            return apres >= l.portee;
        });
    }

    // ================================================================ niveau 1, accroupi : le tourbillon

    /** Un tourbillon : où il est, vers où il file, depuis quand ; ce qu'il tient, quand il l'a pris, et d'où. */
    private static final class Tourbillon {
        final ServerLevel monde;
        Vec3 pos;
        final Vec3 dir;
        final int debut;
        int prise = -1;
        int quand;
        Vec3 sol;

        Tourbillon(ServerLevel monde, Vec3 pos, Vec3 dir, int debut) {
            this.monde = monde;
            this.pos = pos;
            this.dir = dir;
            this.debut = debut;
        }
    }

    private static final Map<UUID, Tourbillon> TOURBILLONS = new HashMap<>();

    private static void tourbillon(ServerPlayer joueur) {
        if (!pret(PRET_ACCROUPI, joueur) || TOURBILLONS.containsKey(joueur.getUUID())) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_ACCROUPI.put(joueur.getUUID(), maintenant + RECHARGE_TOURBILLON);
        Vec3 regard = joueur.getLookAngle();
        Vec3 dir = new Vec3(regard.x, 0, regard.z);
        dir = dir.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : dir.normalize();
        Tourbillon t = new Tourbillon(joueur.serverLevel(), joueur.position().add(dir.scale(1.2)), dir, maintenant);
        TOURBILLONS.put(joueur.getUUID(), t);
        effet(joueur, E_TOURBILLON, 0, t.pos, dir, -1);
        son(t.monde, t.pos, SoundEvents.BREEZE_WHIRL, 1.6F, 1.2F);
        son(t.monde, t.pos, SoundEvents.BREEZE_CHARGE, 1.2F, 1.3F);
    }

    /** Un tick du tourbillon : il file au ras du sol, attrape le premier qu'il croise, le soulève, puis le lâche. */
    private static boolean tourbillonner(ServerPlayer joueur, Tourbillon t, int maintenant) {
        if (t.prise < 0) {
            Vec3 suivant = t.pos.add(t.dir.scale(VITESSE_TOURBILLON));
            HitResult mur = t.monde.clip(new ClipContext(t.pos.add(0, 1, 0), suivant.add(0, 1, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            boolean fin = mur.getType() != HitResult.Type.MISS || (maintenant - t.debut) * VITESSE_TOURBILLON >= PORTEE_TOURBILLON;
            if (mur.getType() == HitResult.Type.MISS) {
                HitResult sol = t.monde.clip(new ClipContext(suivant.add(0, 2.5, 0), suivant.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
                t.pos = sol.getType() == HitResult.Type.MISS ? suivant : sol.getLocation();
            }
            LivingEntity proie = t.monde.getEntitiesOfClass(LivingEntity.class, new AABB(t.pos, t.pos).inflate(1.6, 2.5, 1.6),
                    e -> touchable(joueur, e)).stream().findFirst().orElse(null);
            if (proie != null) {
                t.prise = proie.getId();
                t.quand = maintenant;
                t.sol = proie.position();
                t.pos = new Vec3(proie.getX(), t.pos.y, proie.getZ());
                blesser(joueur, proie, DEGATS_TOURBILLON);
                envoyer(t.monde, new Effet(joueur.getUUID(), E_TOURBILLON, 1, v(t.pos), v(t.dir), proie.getId()), t.pos);
                son(t.monde, t.pos, SoundEvents.BREEZE_INHALE, 1.6F, 1F);
                return false;
            }
            envoyer(t.monde, new Effet(joueur.getUUID(), E_TOURBILLON, fin ? 3 : 0, v(t.pos), v(t.dir), -1), t.pos);
            return fin;
        }
        int age = maintenant - t.quand;
        Entity e = t.monde.getEntity(t.prise);
        if (!(e instanceof LivingEntity proie) || !proie.isAlive() || age > MONTEE + EN_HAUT) {
            // il le lâche de là-haut : la chute fera le reste
            if (e instanceof LivingEntity proie && proie.isAlive()) {
                proie.setDeltaMovement(0, -0.1, 0);
                proie.hurtMarked = true;
                proie.fallDistance = 0;
            }
            envoyer(t.monde, new Effet(joueur.getUUID(), E_TOURBILLON, 3, v(t.pos), v(t.dir), -1), t.pos);
            son(t.monde, t.pos, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.2F, 1.4F);
            return true;
        }
        double k = Math.min(1, age / (double) MONTEE);
        double haut = HAUT_TOURBILLON * k * k * (3 - 2 * k);
        // il tourne en montant
        double a = age * 0.9, r = 0.35 * (1 - k * 0.5);
        Vec3 p = new Vec3(t.sol.x + Math.cos(a) * r, t.sol.y + haut, t.sol.z + Math.sin(a) * r);
        proie.teleportTo(p.x, p.y, p.z);
        proie.setDeltaMovement(Vec3.ZERO);
        proie.fallDistance = 0;
        proie.setYRot(proie.getYRot() + 35);
        proie.hurtMarked = true;
        envoyer(t.monde, new Effet(joueur.getUUID(), E_TOURBILLON, 2, v(t.pos), v(t.dir), proie.getId()), t.pos);
        return false;
    }

    // ================================================================ niveau 2, accroupi : la répulsion céleste

    /** Une répulsion qui se prépare : depuis quand, son rayon, ses dégâts. */
    private record Repulsion(int debut, double rayon, float degats) {
    }

    private static final Map<UUID, Repulsion> REPULSIONS = new HashMap<>();

    /**
     * Bras en T : le vent se ramasse autour de lui (RAMASSE ticks), puis la sphère éclate : tout ce qui est dedans prend et
     * vole loin. Le niveau 3 s'en sert aussi, en plus grand ({@code direct} : sans attendre, après le piqué).
     */
    static void repulsion(ServerPlayer joueur, double rayon, float degats, boolean recharge, boolean ramasser) {
        if (REPULSIONS.containsKey(joueur.getUUID())) {
            return;
        }
        if (recharge) {
            if (!pret(PRET_ACCROUPI, joueur)) {
                return;
            }
            PRET_ACCROUPI.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_REPULSION);
        }
        int maintenant = joueur.server.getTickCount();
        REPULSIONS.put(joueur.getUUID(), new Repulsion(ramasser ? maintenant : maintenant - RAMASSE, rayon, degats));
        if (ramasser) {
            effet(joueur, E_REPULSION, 0, joueur.position().add(0, 1.1, 0), Vec3.ZERO, (int) Math.round(rayon));
            son(joueur.serverLevel(), joueur.position(), SoundEvents.BREEZE_INHALE, 2F, 0.7F);
        }
    }

    /** La sphère éclate. */
    private static void eclater(ServerPlayer joueur, Repulsion r) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 c = joueur.position().add(0, 1.1, 0);
        effet(joueur, E_REPULSION, 1, c, Vec3.ZERO, (int) Math.round(r.rayon()));
        son(monde, c, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 4F, 0.6F);
        son(monde, c, SoundEvents.WIND_CHARGE_BURST.value(), 3F, 0.5F);
        son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r.rayon()), e -> touchable(joueur, e))) {
            Vec3 loin = milieu(e).subtract(c);
            double d = loin.length();
            if (d > r.rayon() + e.getBbWidth() * 0.5) {
                continue;
            }
            blesser(joueur, e, r.degats());
            Vec3 dehors = loin.horizontalDistanceSqr() < 0.01 ? new Vec3(1, 0, 0) : new Vec3(loin.x, 0, loin.z).normalize();
            double force = 2.6 - 1.2 * Math.min(1, d / r.rayon());
            e.setDeltaMovement(dehors.x * force, 1.0, dehors.z * force);
            e.hurtMarked = true;
        }
        monde.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, c.x, c.y, c.z, 1, 0, 0, 0, 0);
    }

    // ================================================================ niveau 2 : les sauts en l'air

    /** Un saut en l'air (le client l'a déjà fait) : on le compte, et un anneau de vent apparaît sous ses pieds. */
    private static void sauter(ServerPlayer joueur, int niveau) {
        int reste = SAUTS.getOrDefault(joueur.getUUID(), SAUTS_EN_L_AIR);
        if (niveau < 2 || reste <= 0 || joueur.onGround()) {
            return;
        }
        SAUTS.put(joueur.getUUID(), reste - 1);
        joueur.fallDistance = 0;
        effet(joueur, E_SAUT, 0, joueur.position(), Vec3.ZERO, reste - 1);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BREEZE_JUMP, 1.2F, 1.2F + 0.1F * (SAUTS_EN_L_AIR - reste));
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        lancers(server, maintenant);
        // qui a le pouvoir : on le redit à tous quand ça change (une liste donnée ou retirée)
        if (maintenant % 20 == 0) {
            List<UUID> l = porteurs(server);
            if (!l.equals(porteurs)) {
                porteurs = l;
                PacketDistributor.sendToAllPlayers(new Porteurs(l));
            }
        }
        FAUCONS.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            return joueur == null || !joueur.isAlive() || joueur.level() != e.getValue().monde || voler(joueur, e.getValue());
        });
        TOURBILLONS.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            return joueur == null || joueur.level() != e.getValue().monde || tourbillonner(joueur, e.getValue(), maintenant);
        });
        REPULSIONS.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null || !joueur.isAlive()) {
                return true;
            }
            if (maintenant - e.getValue().debut() < RAMASSE) {
                // bras en T, il ne bouge plus pendant que le vent se ramasse
                joueur.setDeltaMovement(0, Math.min(0, joueur.getDeltaMovement().y), 0);
                joueur.hurtMarked = true;
                return false;
            }
            eclater(joueur, e.getValue());
            return true;
        });
        // retombé au sol (après l'avoir quitté), ou jamais parti au bout de 3 s : les chutes comptent de nouveau
        SANS_CHUTE.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            int[] v = e.getValue();
            if (joueur == null || maintenant - v[0] > 30 * 20) {
                return true;
            }
            if (!joueur.onGround()) {
                v[1] = 1;
                return false;
            }
            return v[1] == 1 ? maintenant - v[0] > 3 : maintenant - v[0] > 3 * 20;
        });
        for (Map.Entry<UUID, Integer> e : EVEILLES.entrySet()) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                continue;
            }
            // au sol, ses sauts en l'air reviennent ; au niveau 3, il garde le droit de voler (un /gamemode le lui enlève)
            if (joueur.onGround()) {
                SAUTS.put(e.getKey(), SAUTS_EN_L_AIR);
            }
            // la hallebarde : elle arrive quand les armes se forment ; perdue, elle revient (une fois par seconde)
            Integer arrivee = ARRIVEE.get(e.getKey());
            if (e.getValue() >= 3 && arrivee != null && maintenant >= arrivee) {
                ARRIVEE.remove(e.getKey());
                donnerHallebarde(joueur);
            } else if (e.getValue() >= 3 && arrivee == null && maintenant % 20 == 0) {
                donnerHallebarde(joueur);
            }
            // au niveau 3, la hallebarde reste forcément dans sa main
            if (e.getValue() >= 3 && !ARRIVEE.containsKey(e.getKey()) && !tient(joueur)) {
                garderEnMain(joueur);
            }
            if (e.getValue() >= 3 && !joueur.getAbilities().mayfly && !joueur.isSpectator()) {
                Abilities a = joueur.getAbilities();
                a.mayfly = true;
                a.setFlyingSpeed(VOL);
                joueur.onUpdateAbilities();
            }
        }
    }

    /** Pas de dégâts de chute, à tous les niveaux (et pour celui qui vient de s'endormir en l'air, jusqu'au sol). */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && (niveau(joueur) > 0 || Vent3.ultiActif(joueur) || SANS_CHUTE.containsKey(joueur.getUUID()))) {
            event.setCanceled(true);
        }
    }

    /**
     * Le clic gauche, éveillé : n'importe quelle arme frappe plus fort (+2, +4, la hallebarde +6) ; aux niveaux 1 et 2, un
     * petit souffle repousse la cible.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (dansPouvoir || !(source.getEntity() instanceof ServerPlayer joueur) || source.getDirectEntity() != joueur
                || !source.is(DamageTypes.PLAYER_ATTACK) || !eveille(joueur)) {
            return;
        }
        int niveau = niveau(joueur);
        LivingEntity cible = event.getEntity();
        if (niveau >= 3) {
            // la hallebarde (un coup à main nue vaut 1) : 13 à pleine charge, 1,2 coup par seconde ; sans elle, rien de plus
            if (tient(joueur)) {
                event.setAmount(event.getAmount() * DEGATS_HALLEBARDE);
            }
            return;
        }
        event.setAmount(event.getAmount() + (niveau >= 2 ? BONUS_2 : BONUS));
        if (niveau < 3) {
            Vec3 dir = cible.position().subtract(joueur.position());
            dir = dir.horizontalDistanceSqr() < 1e-4 ? Pouvoirs.devant(joueur) : new Vec3(dir.x, 0, dir.z).normalize();
            // un souffle léger : il pousse à peine plus qu'un coup normal
            double force = niveau >= 2 ? 0.16 : 0.1;
            cible.setDeltaMovement(cible.getDeltaMovement().add(dir.x * force, 0.03, dir.z * force));
            cible.hurtMarked = true;
            effet(joueur, E_SOUFFLE, niveau, milieu(cible), dir, cible.getId());
            son(joueur.serverLevel(), cible.position(), SoundEvents.BREEZE_DEFLECT, 0.7F, 1.4F);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        EVEILLES.remove(id);
        TOURBILLONS.remove(id);
        REPULSIONS.remove(id);
        FAUCONS.remove(id);
        SAUTS.remove(id);
        SANS_CHUTE.remove(id);
        if (event.getEntity() instanceof ServerPlayer joueur) {
            reprendreHallebarde(joueur);
        }
        ARRIVEE.remove(id);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        PRET_DROIT.clear();
        PRET_ACCROUPI.clear();
        PRET_FAUCON.clear();
        SAUTS.clear();
        LANCERS.clear();
        FAUCONS.clear();
        TOURBILLONS.clear();
        REPULSIONS.clear();
        SANS_CHUTE.clear();
        ARRIVEE.clear();
    }

    private Vent() {
    }
}
