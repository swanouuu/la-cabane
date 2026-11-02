package fr.cabane.pouvoir;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientSusano;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Les pouvoirs : chacun a sa liste blanche, tenue par les OP ({@code /pouvoir susano ajouter <joueur> <couleur>}).
 * Un élu appuie sur sa touche « Éveil » (dans les options, Beauvais) pour réveiller son pouvoir, et encore pour le
 * rendormir. Pour l'instant il n'y a que le Susano, en deux listes : « susano » (niveau 1 : une cage de côtes et un
 * bras) et « susano2 » (la touche Éveil passe d'abord au niveau 1, puis au niveau 2 : une cage plus grande, une tête et
 * deux bras, qui frappent plus fort, à gauche puis à droite, et qui écrasent au sol ce qu'ils tiennent). Le serveur
 * tient qui est éveillé et fait les dégâts ; ce qu'on voit est dessiné chez chaque joueur ({@link ClientSusano}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Pouvoirs {
    /** Les pouvoirs qui existent (une liste blanche chacun ; un joueur n'est que dans une). */
    public static final List<String> LISTE = List.of("susano", "susano2", Hachibi.NOM);

    /** Les couleurs possibles (en français ou en anglais dans la commande) : la teinte du corps, puis celle du contour. */
    public enum Couleur {
        ROUGE("rouge", "red", 0xE8401C, 0xFFB070),
        BLEU("bleu", "blue", 0x2A5CFF, 0x9CC8FF),
        VIOLET("violet", "purple", 0x6A2CFF, 0xE0B8FF),
        NOIR("noir", "black", 0x020203, 0xFFFFFF),
        VERT("vert", "green", 0x22C454, 0xB0FFB8),
        ORANGE("orange", "orange", 0xFF7A10, 0xFFD080),
        JAUNE("jaune", "yellow", 0xFFD21A, 0xFFF4B0),
        BLANC("blanc", "white", 0xD8E4F0, 0xFFFFFF),
        ROSE("rose", "pink", 0xFF4FA8, 0xFFC4E4),
        CYAN("cyan", "cyan", 0x18D6E8, 0xB8FAFF);

        public final String nom;
        public final String anglais;
        public final int corps;
        public final int contour;

        Couleur(String nom, String anglais, int corps, int contour) {
            this.nom = nom;
            this.anglais = anglais;
            this.corps = corps;
            this.contour = contour;
        }

        public static Couleur lire(String texte) {
            String t = texte.toLowerCase(Locale.ROOT);
            for (Couleur c : values()) {
                if (c.nom.equals(t) || c.anglais.equals(t)) {
                    return c;
                }
            }
            return null;
        }

        static List<String> noms() {
            List<String> noms = new ArrayList<>();
            for (Couleur c : values()) {
                noms.add(c.nom);
                if (!c.anglais.equals(c.nom)) {
                    noms.add(c.anglais);
                }
            }
            return noms;
        }
    }

    /** Ce que le Susano fait selon son niveau : le niveau 2 frappe plus fort, plus loin et plus vite. */
    private record Force(double portee, float frappe, int recharge, int portePrise, float broie, float jet, double elanJet,
                         float charge, double elanCharge) {
    }

    private static final Force NIVEAU_1 = new Force(5, 9, 12, 6, 1.5F, 5, 2.4, 8, 1.6);
    private static final Force NIVEAU_2 = new Force(6.5, 14, 8, 8, 2.5F, 8, 2.9, 12, 1.9);
    private static final float ECRASEMENT = 16;
    private static final float ONDE = 6;

    private static Force force(int niveau) {
        return niveau >= 2 ? NIVEAU_2 : NIVEAU_1;
    }

    /** Qui est éveillé en ce moment, et à quel niveau (ça ne survit pas à une déconnexion). */
    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Map<UUID, Integer> DERNIERE_FRAPPE = new HashMap<>();
    /** De quel côté le niveau 2 a frappé la dernière fois (il alterne). */
    private static final Map<UUID, Integer> COTE = new HashMap<>();

    private static int niveau(ServerPlayer joueur) {
        return EVEILLES.getOrDefault(joueur.getUUID(), 0);
    }

    // ================================================================ la liste blanche, gardée dans le monde

    public static final class Donnees extends SavedData {
        private static final String NOM = "cabane_pouvoirs";

        public record Elu(String pouvoir, Couleur couleur, String nom) {
            /** Le niveau le plus haut que ce joueur peut atteindre. */
            int niveauMax() {
                return pouvoir.equals("susano2") ? 2 : 1;
            }
        }

        final Map<UUID, Elu> elus = new LinkedHashMap<>();

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::charger, null), NOM);
        }

        private static Donnees charger(CompoundTag tag, HolderLookup.Provider registres) {
            Donnees d = new Donnees();
            for (Tag t : tag.getList("elus", Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) t;
                Couleur c = Couleur.lire(e.getString("couleur"));
                d.elus.put(e.getUUID("uuid"), new Elu(e.getString("pouvoir"), c == null ? Couleur.VIOLET : c, e.getString("nom")));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            ListTag liste = new ListTag();
            elus.forEach((uuid, elu) -> {
                CompoundTag e = new CompoundTag();
                e.putUUID("uuid", uuid);
                e.putString("pouvoir", elu.pouvoir());
                e.putString("couleur", elu.couleur().nom);
                e.putString("nom", elu.nom());
                liste.add(e);
            });
            tag.put("elus", liste);
            return tag;
        }
    }

    // ================================================================ réseau

    /** Client → serveur : la touche Éveil. */
    public record Eveil() implements CustomPacketPayload {
        public static final Type<Eveil> TYPE = new Type<>(Cabane.id("eveil"));
        public static final StreamCodec<ByteBuf, Eveil> CODEC = StreamCodec.unit(new Eveil());

        @Override
        public Type<Eveil> type() {
            return TYPE;
        }
    }

    /** Client → serveur : un clic gauche, pouvoir éveillé. */
    public record Frappe() implements CustomPacketPayload {
        public static final Type<Frappe> TYPE = new Type<>(Cabane.id("pouvoir_frappe"));
        public static final StreamCodec<ByteBuf, Frappe> CODEC = StreamCodec.unit(new Frappe());

        @Override
        public Type<Frappe> type() {
            return TYPE;
        }
    }

    /** Client → serveur : un clic droit, pouvoir éveillé (attraper ce qui est devant ; avec une prise : lâcher, ou écraser au niveau 2). */
    public record Attraper() implements CustomPacketPayload {
        public static final Type<Attraper> TYPE = new Type<>(Cabane.id("pouvoir_attraper"));
        public static final StreamCodec<ByteBuf, Attraper> CODEC = StreamCodec.unit(new Attraper());

        @Override
        public Type<Attraper> type() {
            return TYPE;
        }
    }

    /** Client → serveur : on vient de s'accroupir, pouvoir éveillé (la charge en avant). */
    public record Charge() implements CustomPacketPayload {
        public static final Type<Charge> TYPE = new Type<>(Cabane.id("pouvoir_charge"));
        public static final StreamCodec<ByteBuf, Charge> CODEC = StreamCodec.unit(new Charge());

        @Override
        public Type<Charge> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → tous : le pouvoir de ce joueur s'éveille, change de niveau ou s'endort. {@code pouvoir} est sa liste
     * blanche (ce qu'il pourra atteindre), {@code niveau} où il en est. {@code annonce} : on joue l'animation (sinon,
     * c'est un joueur qui arrive et qui doit juste voir ce qui est déjà là).
     */
    public record Etat(UUID joueur, String pouvoir, int couleur, int niveau, boolean eveille, boolean annonce) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("pouvoir_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::joueur, ByteBufCodecs.STRING_UTF8, Etat::pouvoir, ByteBufCodecs.VAR_INT, Etat::couleur,
                ByteBufCodecs.VAR_INT, Etat::niveau, ByteBufCodecs.BOOL, Etat::eveille, ByteBufCodecs.BOOL, Etat::annonce, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    public static final int FRAPPE = 0;
    public static final int CHARGE = 1;
    public static final int JET = 2;
    public static final int PRISE = 3;
    public static final int ECRASE = 4;
    /** L'écrasement touche le sol à ce moment-là (en ticks après le clic) : l'animation est calée dessus. */
    public static final int IMPACT = 8;

    /**
     * Serveur → ceux qui voient ce joueur : un geste de son Susano. Pour une frappe, {@code cible} est le bras (0 droit,
     * 1 gauche) ; pour une prise, l'entité tenue (-1 quand il la lâche).
     */
    public record Geste(UUID joueur, int geste, int cible) implements CustomPacketPayload {
        public static final Type<Geste> TYPE = new Type<>(Cabane.id("pouvoir_geste"));
        public static final StreamCodec<ByteBuf, Geste> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Geste::joueur, ByteBufCodecs.VAR_INT, Geste::geste, ByteBufCodecs.VAR_INT, Geste::cible, Geste::new);

        @Override
        public Type<Geste> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Eveil.TYPE, Eveil.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        basculer(joueur);
                    }
                }))
                .playToServer(Frappe.TYPE, Frappe.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        frapper(joueur);
                    }
                }))
                .playToServer(Attraper.TYPE, Attraper.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        attraper(joueur);
                    }
                }))
                .playToServer(Charge.TYPE, Charge.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        charger(joueur);
                    }
                }))
                .playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientSusano.recevoir(p)))
                .playToClient(Geste.TYPE, Geste.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientSusano.geste(p)));
    }

    // ================================================================ s'éveiller, monter de niveau, s'endormir

    private static void envoyerEtat(ServerPlayer joueur, Donnees.Elu elu, int niveau, boolean annonce) {
        PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), elu.pouvoir(), elu.couleur().ordinal(), niveau, niveau > 0, annonce));
    }

    /** La touche Éveil : endormi → niveau 1 → niveau 2 (si le joueur l'a) → endormi. */
    private static void basculer(ServerPlayer joueur) {
        Donnees.Elu elu = Donnees.get(joueur.server).elus.get(joueur.getUUID());
        if (elu == null) {
            joueur.displayClientMessage(Component.literal("Rien ne s'éveille en toi… pour l'instant.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
            return;
        }
        int niveau = niveau(joueur);
        if (niveau >= elu.niveauMax()) {
            endormir(joueur);
            return;
        }
        EVEILLES.put(joueur.getUUID(), niveau + 1);
        armure(joueur, niveau + 1);
        if (elu.pouvoir().equals(Hachibi.NOM)) {
            Hachibi.eveiller(joueur);
        }
        envoyerEtat(joueur, elu, niveau + 1, true);
    }

    private static boolean hachibi(ServerPlayer joueur) {
        Donnees.Elu elu = Donnees.get(joueur.server).elus.get(joueur.getUUID());
        return elu != null && elu.pouvoir().equals(Hachibi.NOM);
    }

    private static final net.minecraft.resources.ResourceLocation ARMURE = Cabane.id("pouvoir_armure");
    private static final net.minecraft.resources.ResourceLocation ROBUSTESSE = Cabane.id("pouvoir_robustesse");
    private static final net.minecraft.resources.ResourceLocation RECUL = Cabane.id("pouvoir_recul");

    /**
     * Un pouvoir éveillé protège comme une armure de diamant complète (sans enchantement) ; le Susano niveau 2, comme
     * de la netherite. Endormi (niveau 0), plus rien.
     */
    private static void armure(ServerPlayer joueur, int niveau) {
        modif(joueur, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, ARMURE, niveau > 0 ? 20 : 0);
        modif(joueur, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS, ROBUSTESSE, niveau == 0 ? 0 : niveau >= 2 ? 12 : 8);
        modif(joueur, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE, RECUL, niveau >= 2 ? 0.4 : 0);
    }

    private static void modif(ServerPlayer joueur, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribut,
                              net.minecraft.resources.ResourceLocation id, double valeur) {
        var a = joueur.getAttribute(attribut);
        if (a == null) {
            return;
        }
        a.removeModifier(id);
        if (valeur != 0) {
            a.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, valeur,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void endormir(ServerPlayer joueur) {
        lacher(joueur, false);
        CHARGES.remove(joueur.getUUID());
        ECRASEMENTS.remove(joueur.getUUID());
        Hachibi.endormir(joueur);
        armure(joueur, 0);
        if (EVEILLES.remove(joueur.getUUID()) != null) {
            Donnees.Elu elu = Donnees.get(joueur.server).elus.get(joueur.getUUID());
            PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), elu == null ? "susano" : elu.pouvoir(),
                    elu == null ? 0 : elu.couleur().ordinal(), 0, false, true));
        }
    }

    // ================================================================ frapper

    static void geste(ServerPlayer joueur, int geste, int cible) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Geste(joueur.getUUID(), geste, cible));
    }

    private static DustParticleOptions poussiere(ServerPlayer joueur) {
        Donnees.Elu elu = Donnees.get(joueur.server).elus.get(joueur.getUUID());
        int teinte = elu == null ? Couleur.VIOLET.corps : elu.couleur() == Couleur.NOIR ? 0x18181C : elu.couleur().corps;
        return new DustParticleOptions(new Vector3f((teinte >> 16 & 255) / 255F, (teinte >> 8 & 255) / 255F, (teinte & 255) / 255F), 2F);
    }

    /** Ce qu'un pouvoir peut toucher : ce qui vit, pas soi-même, pas ses alliés, pas ce qu'une main tient déjà. */
    static boolean cible(ServerPlayer joueur, LivingEntity e) {
        return e != joueur && e.isAlive() && !e.isAlliedTo(joueur) && TENUS.values().stream().noneMatch(p -> p.id() == e.getId());
    }

    static Vec3 devant(ServerPlayer joueur) {
        float lacet = joueur.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
    }

    /**
     * Le grand bras balaie un demi-cercle devant le joueur : tout ce qui vit dedans prend cher et vole. Au niveau 2, les
     * deux bras frappent chacun leur tour. Si la main tient quelqu'un, le clic gauche le jette à la place.
     */
    private static void frapper(ServerPlayer joueur) {
        int niveau = niveau(joueur);
        if (niveau > 0 && hachibi(joueur)) {
            Hachibi.griffe(joueur);
            return;
        }
        if (niveau == 0 || ECRASEMENTS.containsKey(joueur.getUUID())) {
            return;
        }
        Force f = force(niveau);
        int maintenant = joueur.server.getTickCount();
        if (maintenant - DERNIERE_FRAPPE.getOrDefault(joueur.getUUID(), -100) < f.recharge()) {
            return;
        }
        DERNIERE_FRAPPE.put(joueur.getUUID(), maintenant);
        if (TENUS.containsKey(joueur.getUUID())) {
            jeter(joueur, f);
            return;
        }
        int cote = niveau >= 2 ? 1 - COTE.getOrDefault(joueur.getUUID(), 1) : 0;
        COTE.put(joueur.getUUID(), cote);
        geste(joueur, FRAPPE, cote);
        DustParticleOptions poussiere = poussiere(joueur);
        Vec3 devant = devant(joueur);
        ServerLevel niveauMonde = joueur.serverLevel();
        for (LivingEntity cible : niveauMonde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(f.portee(), 2, f.portee()),
                e -> cible(joueur, e))) {
            Vec3 vers = cible.position().subtract(joueur.position());
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            if (plat.length() > f.portee() || (plat.lengthSqr() > 0.01 && plat.normalize().dot(devant) < -0.35)) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), f.frappe());
            cible.knockback(niveau >= 2 ? 1.8 : 1.3, -devant.x, -devant.z);
            niveauMonde.sendParticles(poussiere, cible.getX(), cible.getY(0.5), cible.getZ(), 14, 0.4, 0.5, 0.4, 0.05);
            niveauMonde.sendParticles(ParticleTypes.SWEEP_ATTACK, cible.getX(), cible.getY(0.5), cible.getZ(), 1, 0, 0, 0, 0);
        }
    }

    // ================================================================ attraper, jeter, écraser

    /** Une prise : l'entité, le tick où la main l'a saisie, et d'où elle a été arrachée. */
    private record Prise(int id, int debut, Vec3 depart) {
    }

    /** Qui tient qui : le joueur → ce que tient la main (droite) de son Susano. */
    private static final Map<UUID, Prise> TENUS = new HashMap<>();
    /** Qui est en train d'écraser sa prise au sol : le joueur → le tick du clic. */
    private static final Map<UUID, Integer> ECRASEMENTS = new HashMap<>();
    /** La main met ce temps (en ticks) à arracher sa prise et à la ramener à elle. */
    private static final int ARRACHER = 7;

    /** Où la main droite tient sa prise : devant, à droite (plus loin et plus haut au niveau 2, le Susano est plus grand). */
    private static Vec3 main(ServerPlayer joueur) {
        boolean grand = niveau(joueur) >= 2;
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return joueur.position().add(devant.scale(grand ? 3.5 : 2.7)).add(droite.scale(grand ? 1.7 : 1.3)).add(0, grand ? 1.3 : 1.15, 0);
    }

    /**
     * La main se referme sur ce qui est le plus près devant. Si elle tient déjà quelque chose : au niveau 1 elle le
     * lâche, au niveau 2 elle l'écrase au sol.
     */
    private static void attraper(ServerPlayer joueur) {
        int niveau = niveau(joueur);
        if (niveau > 0 && hachibi(joueur)) {
            Hachibi.bete(joueur);
            return;
        }
        if (niveau == 0 || ECRASEMENTS.containsKey(joueur.getUUID())) {
            return;
        }
        if (TENUS.containsKey(joueur.getUUID())) {
            if (niveau >= 2) {
                ECRASEMENTS.put(joueur.getUUID(), joueur.server.getTickCount());
                geste(joueur, ECRASE, -1);
            } else {
                lacher(joueur, true);
            }
            return;
        }
        Force f = force(niveau);
        Vec3 devant = devant(joueur);
        LivingEntity proie = null;
        double meilleure = Double.MAX_VALUE;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class,
                joueur.getBoundingBox().inflate(f.portePrise(), 3, f.portePrise()), e -> cible(joueur, e))) {
            Vec3 vers = e.position().subtract(joueur.position());
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            double d = plat.length();
            if (d <= f.portePrise() && d < meilleure && (d < 0.5 || plat.normalize().dot(devant) > 0.3)) {
                meilleure = d;
                proie = e;
            }
        }
        if (proie == null) {
            joueur.displayClientMessage(Component.literal("Personne à attraper devant toi.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        TENUS.put(joueur.getUUID(), new Prise(proie.getId(), joueur.server.getTickCount(), proie.position()));
        geste(joueur, PRISE, proie.getId());
    }

    private static void lacher(ServerPlayer joueur, boolean prevenir) {
        ECRASEMENTS.remove(joueur.getUUID());
        if (TENUS.remove(joueur.getUUID()) != null && prevenir) {
            geste(joueur, PRISE, -1);
        }
    }

    /** Le bras lance sa prise droit devant, là où le joueur regarde. */
    private static void jeter(ServerPlayer joueur, Force f) {
        Prise prise = TENUS.remove(joueur.getUUID());
        geste(joueur, JET, -1);
        if (prise == null || !(joueur.serverLevel().getEntity(prise.id()) instanceof LivingEntity proie)) {
            return;
        }
        proie.hurt(joueur.damageSources().playerAttack(joueur), f.jet());
        proie.setDeltaMovement(joueur.getLookAngle().scale(f.elanJet()).add(0, 0.55, 0));
        proie.hurtMarked = true;
        proie.fallDistance = 0;
        joueur.serverLevel().sendParticles(poussiere(joueur), proie.getX(), proie.getY(0.5), proie.getZ(), 24, 0.5, 0.6, 0.5, 0.1);
    }

    /** Le sol sous ce point (au plus 10 blocs plus bas) : la hauteur où poser les pieds, et le bloc frappé. */
    private static double sol(ServerLevel monde, Vec3 point, BlockState[] bloc) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(point.x, point.y + 1, point.z).mutable();
        for (int i = 0; i < 12; i++) {
            BlockState etat = monde.getBlockState(pos);
            if (!etat.getCollisionShape(monde, pos).isEmpty()) {
                bloc[0] = etat;
                return pos.getY() + etat.getCollisionShape(monde, pos).max(net.minecraft.core.Direction.Axis.Y);
            }
            pos.move(0, -1, 0);
        }
        return point.y - 1;
    }

    /** BOUM : la main abat sa prise contre le sol ; tout autour est soufflé. */
    private static void impact(ServerPlayer joueur, Prise prise) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 main = main(joueur);
        BlockState[] bloc = {null};
        double y = sol(monde, main, bloc);
        Entity e = monde.getEntity(prise.id());
        if (e instanceof LivingEntity proie && proie.isAlive()) {
            proie.teleportTo(main.x, y, main.z);
            proie.hurt(joueur.damageSources().playerAttack(joueur), ECRASEMENT);
            proie.setDeltaMovement(0, 0.35, 0);
            proie.hurtMarked = true;
            proie.fallDistance = 0;
        }
        for (LivingEntity autour : monde.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(main.x - 4.5, y - 1, main.z - 4.5,
                main.x + 4.5, y + 3, main.z + 4.5), a -> cible(joueur, a) && a.getId() != prise.id())) {
            Vec3 loin = autour.position().subtract(main.x, y, main.z);
            if (loin.horizontalDistance() > 4.5) {
                continue;
            }
            autour.hurt(joueur.damageSources().playerAttack(joueur), ONDE);
            autour.knockback(1.5, -loin.x, -loin.z);
            autour.setDeltaMovement(autour.getDeltaMovement().add(0, 0.6, 0));
            autour.hurtMarked = true;
        }
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, main.x, y + 0.2, main.z, 1, 0, 0, 0, 0);
        monde.sendParticles(poussiere(joueur), main.x, y + 0.5, main.z, 60, 2, 0.4, 2, 0.15);
        monde.sendParticles(ParticleTypes.CLOUD, main.x, y + 0.2, main.z, 40, 2.2, 0.1, 2.2, 0.08);
        if (bloc[0] != null) {
            monde.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, bloc[0]), main.x, y + 0.2, main.z, 120, 1.6, 0.3, 1.6, 0.4);
        }
        monde.playSound(null, main.x, y, main.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.2F, 0.6F);
        monde.playSound(null, main.x, y, main.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1F, 0.5F);
    }

    // ================================================================ la charge

    /** Qui charge en ce moment : le joueur → le tick où la charge s'arrête, sa direction, et ce qu'elle a déjà touché. */
    private record Elan(int fin, Vec3 direction, Set<Integer> touches) {
    }

    private static final Map<UUID, Elan> CHARGES = new HashMap<>();
    private static final Map<UUID, Integer> DERNIERE_CHARGE = new HashMap<>();
    public static final int RECHARGE_CHARGE = 50;

    /** Le Susano pique droit devant : le joueur file, et tout ce qu'il traverse est fauché. */
    private static void charger(ServerPlayer joueur) {
        int niveau = niveau(joueur);
        if (niveau > 0 && hachibi(joueur)) {
            Hachibi.accroupi(joueur);
            return;
        }
        if (niveau == 0) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant - DERNIERE_CHARGE.getOrDefault(joueur.getUUID(), -1000) < RECHARGE_CHARGE) {
            return;
        }
        DERNIERE_CHARGE.put(joueur.getUUID(), maintenant);
        Vec3 direction = devant(joueur);
        CHARGES.put(joueur.getUUID(), new Elan(maintenant + 10, direction, new HashSet<>()));
        joueur.setDeltaMovement(direction.scale(force(niveau).elanCharge()).add(0, 0.12, 0));
        joueur.hurtMarked = true;
        geste(joueur, CHARGE, -1);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // ce que les mains tiennent est arraché jusqu'à la main, la suit (et se fait broyer) ; ou s'écrase au sol
        for (Map.Entry<UUID, Prise> entree : new ArrayList<>(TENUS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Prise prise = entree.getValue();
            Entity e = joueur == null ? null : joueur.serverLevel().getEntity(prise.id());
            if (joueur == null || !(e instanceof LivingEntity proie) || !proie.isAlive() || proie.distanceTo(joueur) > 14) {
                if (joueur != null) {
                    lacher(joueur, true);
                } else {
                    TENUS.remove(entree.getKey());
                }
                continue;
            }
            Vec3 main = main(joueur);
            Integer ecrase = ECRASEMENTS.get(joueur.getUUID());
            if (ecrase != null) {
                int age = maintenant - ecrase;
                if (age >= IMPACT) {
                    TENUS.remove(joueur.getUUID());
                    ECRASEMENTS.remove(joueur.getUUID());
                    impact(joueur, prise);
                    continue;
                }
                // la main lève sa prise bien haut avant de l'abattre
                main = main.add(0, 3.2 * Mth.sin((float) Math.min(age / 6.0, 1) * Mth.HALF_PI), 0);
            }
            int age = maintenant - prise.debut();
            if (age < ARRACHER) {
                float k = age / (float) ARRACHER;
                main = prise.depart().lerp(main, k * k * (3 - 2 * k));
            }
            proie.teleportTo(main.x, main.y - proie.getBbHeight() / 2, main.z);
            proie.setDeltaMovement(Vec3.ZERO);
            proie.fallDistance = 0;
            if (ecrase == null && age > ARRACHER && maintenant % 20 == 0) {
                proie.hurt(joueur.damageSources().playerAttack(joueur), force(niveau(joueur)).broie());
            }
        }
        // les charges fauchent ce qu'elles traversent
        for (Map.Entry<UUID, Elan> charge : new ArrayList<>(CHARGES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(charge.getKey());
            Elan elan = charge.getValue();
            if (joueur == null || maintenant > elan.fin()) {
                CHARGES.remove(charge.getKey());
                continue;
            }
            joueur.fallDistance = 0;
            for (LivingEntity cible : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class,
                    joueur.getBoundingBox().inflate(1.4).expandTowards(elan.direction().scale(1.5)), e -> cible(joueur, e))) {
                if (elan.touches().add(cible.getId())) {
                    cible.hurt(joueur.damageSources().playerAttack(joueur), force(niveau(joueur)).charge());
                    cible.knockback(1.6, -elan.direction().x, -elan.direction().z);
                    joueur.serverLevel().sendParticles(poussiere(joueur), cible.getX(), cible.getY(0.5), cible.getZ(), 16, 0.4, 0.5, 0.4, 0.08);
                }
            }
        }
    }

    // ================================================================ commandes

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> racine = Commands.literal("pouvoir").requires(s -> s.hasPermission(2));
        for (String pouvoir : LISTE) {
            // Hachibi est toujours rouge : pas de couleur à choisir
            var joueur = Commands.argument("joueur", EntityArgument.player());
            if (pouvoir.equals(Hachibi.NOM)) {
                joueur.executes(c -> ajouter(c, pouvoir, Couleur.ROUGE));
            } else {
                joueur.then(Commands.argument("couleur", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Couleur.noms(), b))
                        .executes(c -> ajouter(c, pouvoir, Couleur.lire(StringArgumentType.getString(c, "couleur")))));
            }
            racine.then(Commands.literal(pouvoir)
                    .then(Commands.literal("ajouter").then(joueur))
                    .then(Commands.literal("retirer").then(Commands.argument("joueur", EntityArgument.player())
                            .executes(Pouvoirs::retirer))));
        }
        racine.then(Commands.literal("liste").executes(Pouvoirs::liste));
        event.getDispatcher().register(racine);
    }

    private static int ajouter(CommandContext<CommandSourceStack> c, String pouvoir, Couleur couleur) throws CommandSyntaxException {
        ServerPlayer joueur = EntityArgument.getPlayer(c, "joueur");
        if (couleur == null) {
            c.getSource().sendFailure(Component.literal("Couleur inconnue. Possibles : " + String.join(", ", Couleur.noms())));
            return 0;
        }
        Donnees d = Donnees.get(c.getSource().getServer());
        Donnees.Elu ancien = d.elus.get(joueur.getUUID());
        if (ancien != null && ancien.pouvoir().equals(Hachibi.NOM) != pouvoir.equals(Hachibi.NOM)) {
            // on change de pouvoir (Susano ↔ Hachibi) : l'ancien s'endort d'abord
            endormir(joueur);
        }
        Donnees.Elu elu = new Donnees.Elu(pouvoir, couleur, joueur.getGameProfile().getName());
        d.elus.put(joueur.getUUID(), elu);
        d.setDirty();
        int niveau = niveau(joueur);
        if (niveau > 0) {
            // déjà éveillé : il change de couleur tout de suite (et redescend au niveau 1 s'il n'a plus le 2)
            int permis = Math.min(niveau, elu.niveauMax());
            EVEILLES.put(joueur.getUUID(), permis);
            armure(joueur, permis);
            envoyerEtat(joueur, elu, permis, false);
        }
        c.getSource().sendSuccess(() -> Component.literal(joueur.getGameProfile().getName() + " a le " + pouvoir + " (" + couleur.nom
                + "). Touche Éveil pour l'activer."), true);
        return 1;
    }

    private static int retirer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer joueur = EntityArgument.getPlayer(c, "joueur");
        endormir(joueur);
        Donnees d = Donnees.get(c.getSource().getServer());
        if (d.elus.remove(joueur.getUUID()) == null) {
            c.getSource().sendFailure(Component.literal(joueur.getGameProfile().getName() + " n'a aucun pouvoir."));
            return 0;
        }
        d.setDirty();
        c.getSource().sendSuccess(() -> Component.literal(joueur.getGameProfile().getName() + " n'a plus de pouvoir."), true);
        return 1;
    }

    private static int liste(CommandContext<CommandSourceStack> c) {
        Donnees d = Donnees.get(c.getSource().getServer());
        if (d.elus.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("Personne n'a de pouvoir."), false);
            return 0;
        }
        d.elus.forEach((uuid, elu) -> c.getSource().sendSuccess(() -> Component.literal(elu.nom() + " : " + elu.pouvoir() + " "
                + elu.couleur().nom + (EVEILLES.containsKey(uuid) ? " (éveillé, niveau " + EVEILLES.get(uuid) + ")" : "")), false));
        return d.elus.size();
    }

    // ================================================================ arrivées, départs, morts

    /** Celui qui arrive voit les pouvoirs déjà éveillés. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer arrivant)) {
            return;
        }
        Donnees d = Donnees.get(arrivant.server);
        EVEILLES.forEach((uuid, niveau) -> {
            Donnees.Elu elu = d.elus.get(uuid);
            if (elu != null) {
                PacketDistributor.sendToPlayer(arrivant, new Etat(uuid, elu.pouvoir(), elu.couleur().ordinal(), niveau, true, false));
            }
        });
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            endormir(joueur);
            DERNIERE_FRAPPE.remove(joueur.getUUID());
            COTE.remove(joueur.getUUID());
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            endormir(joueur);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        DERNIERE_FRAPPE.clear();
        COTE.clear();
        TENUS.clear();
        ECRASEMENTS.clear();
        CHARGES.clear();
        DERNIERE_CHARGE.clear();
    }

    private Pouvoirs() {
    }
}
