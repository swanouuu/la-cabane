package fr.cabane.mission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Une mission en cours, dans sa scène : qui y joue (les héros, les méchants), qui est en cellule, où sont les objectifs (posés,
 * ou dans la poche de quelqu'un), l'alarme, les lasers, les caméras, les projecteurs, les pièges, les réservoirs, le compte à
 * rebours, et ce qu'on affiche en haut à droite.
 */
final class Session {
    static final String CLE = "cabane_mission";
    private static final DustParticleOptions ROUGE = new DustParticleOptions(new Vector3f(1F, 0.08F, 0.12F), 0.7F);
    private static final DustParticleOptions FAISCEAU = new DustParticleOptions(new Vector3f(1F, 0.97F, 0.75F), 1.6F);

    final Scene scene;
    final ServerLevel level;
    final int numero;
    final Set<UUID> heros = new LinkedHashSet<>();
    final Set<UUID> mechants = new LinkedHashSet<>();
    final Set<UUID> captures = new LinkedHashSet<>();
    /** L'objet posé dans la scène (son affichage), ou absent s'il est dans une poche. */
    private final Map<String, UUID> poses = new HashMap<>();
    private final boolean[] sabotes;
    private UUID saboteur;
    private int saboteurReservoir, saboteurTicks;
    private int restant;
    private boolean reussie, ratee;
    private long alarmeJusqua, derniereAlarme;
    UUID coureurGong;
    private UUID otage;
    private final long[] pieges;
    private final Map<UUID, String> hudEnvoye = new HashMap<>();

    Session(Scene scene, ServerLevel level, int numero) {
        this.scene = scene;
        this.level = level;
        this.numero = numero;
        sabotes = new boolean[scene.reservoirs.size()];
        pieges = new long[scene.pieges.size()];
        restant = scene.compteRebours * 20;
    }

    /** Les gens, les objectifs : tout est posé à neuf. */
    void peupler() {
        for (Scene.Pnj p : scene.pnj) {
            Entity e;
            if (p.type().equals("chien")) {
                MissionChien c = Missions.CHIEN.get().create(level);
                e = c == null ? null : c.preparer(scene.id, p);
            } else {
                MissionGarde g = Missions.GARDE.get().create(level);
                e = g == null ? null : g.preparer(scene.id, p);
                if (g != null && g.type() == MissionGarde.OTAGE) {
                    otage = g.getUUID();
                }
            }
            if (e != null) {
                e.addTag(scene.tag());
                e.addTag(scene.tag(numero));
                level.addFreshEntity(e);
            }
        }
        for (Scene.Objet o : scene.objets) {
            poser(o);
        }
    }

    // ================================================================== qui joue

    boolean finie() {
        return reussie;
    }

    boolean estHeros(ServerPlayer j) {
        return heros.contains(j.getUUID());
    }

    /** Un héros qu'on peut voir et attraper : là, pas en cellule, pas spectateur. */
    boolean estCible(ServerPlayer j) {
        return heros.contains(j.getUUID()) && !captures.contains(j.getUUID()) && j.level() == level && j.isAlive() && !j.isSpectator()
                && scene.etendue().contains(j.position());
    }

    List<ServerPlayer> cibles(ServerLevel l) {
        List<ServerPlayer> r = new ArrayList<>();
        for (UUID u : heros) {
            ServerPlayer j = l.getServer().getPlayerList().getPlayer(u);
            if (j != null && estCible(j)) {
                r.add(j);
            }
        }
        return r;
    }

    ServerPlayer cible(ServerLevel l, UUID u) {
        ServerPlayer j = l.getServer().getPlayerList().getPlayer(u);
        return j != null && estCible(j) ? j : null;
    }

    /** Les joueurs de la mission (héros et méchants) qui sont dans la scène. */
    List<ServerPlayer> participants() {
        List<ServerPlayer> r = new ArrayList<>();
        for (UUID u : concat()) {
            ServerPlayer j = level.getServer().getPlayerList().getPlayer(u);
            if (j != null && j.level() == level) {
                r.add(j);
            }
        }
        return r;
    }

    private List<UUID> concat() {
        List<UUID> r = new ArrayList<>(heros);
        r.addAll(mechants);
        return r;
    }

    void quitter(UUID u) {
        heros.remove(u);
        mechants.remove(u);
        captures.remove(u);
        hudEnvoye.remove(u);
    }

    // ================================================================== les gardes, l'alarme

    private List<MissionGarde> gardes() {
        return level.getEntitiesOfClass(MissionGarde.class, scene.etendue(), g -> g.getTags().contains(scene.tag(numero)));
    }

    /** Les gardes autour de {@code source} t'ont vu aussi. */
    void alerterAutour(ServerLevel l, MissionGarde source, ServerPlayer h, double rayon) {
        for (MissionGarde g : gardes()) {
            if (g != source && (g.type() == MissionGarde.GARDE || g.type() == MissionGarde.ARCHER) && !g.assomme() && g.etat() != MissionGarde.ALERTE
                    && g.distanceTo(source) < rayon) {
                g.reperer(l, this, h);
            }
        }
    }

    boolean alarmeActive(ServerLevel l) {
        return l.getGameTime() < alarmeJusqua;
    }

    /** L'alarme : la sirène (le bourdon chez les ninjas), et les gardes du coin (de toute la scène si {@code forte}) foncent. */
    void alarme(ServerLevel l, Vec3 ou, ServerPlayer h, boolean forte) {
        long t = l.getGameTime();
        if (t - derniereAlarme < 100) {
            return;
        }
        derniereAlarme = t;
        alarmeJusqua = t + 400;
        boolean ninjas = scene.id.startsWith("anbu");
        for (int k = 0; k < 3; k++) {
            l.playSound(null, ou.x, ou.y + 2, ou.z, ninjas ? SoundEvents.BELL_BLOCK : SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.HOSTILE, 4F,
                    ninjas ? 0.7F : 1.6F - k * 0.3F);
        }
        for (ServerPlayer j : participants()) {
            j.displayClientMessage(Component.literal("⚠ ALARME ! ⚠").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            j.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.HOSTILE, 0.8F, 0.5F);
        }
        for (MissionGarde g : gardes()) {
            if ((g.type() != MissionGarde.GARDE && g.type() != MissionGarde.ARCHER) || g.assomme()) {
                continue;
            }
            if (forte || g.position().distanceTo(ou) < 40) {
                if (h != null && estCible(h)) {
                    g.reperer(l, this, h);
                } else {
                    g.soupcon(l, ou);
                }
            }
        }
    }

    /** Le gong du château : tout le château se réveille. */
    void sonnerGong(ServerLevel l, Entity qui, ServerPlayer h) {
        Vec3 g = scene.gong.getCenter();
        for (int k = 0; k < 4; k++) {
            l.playSound(null, g.x, g.y, g.z, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 6F, 0.5F + k * 0.05F);
        }
        l.sendParticles(ParticleTypes.NOTE, g.x, g.y + 1, g.z, 12, 0.6, 0.6, 0.6, 1);
        for (ServerPlayer j : participants()) {
            j.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 15));
            j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Tout le château se réveille !").withStyle(ChatFormatting.GOLD)));
            j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LE GONG !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }
        derniereAlarme = 0;
        alarme(l, h != null ? h.position() : qui.position(), h, true);
    }

    /** /mission alerte : tous les gardes en chasse, chacun après le héros le plus proche. */
    void poursuite() {
        List<ServerPlayer> cs = cibles(level);
        alarmeJusqua = level.getGameTime() + 400;
        for (ServerPlayer j : participants()) {
            j.displayClientMessage(Component.literal("⚠ TOUS LES GARDES SONT EN ALERTE ⚠").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
        }
        if (cs.isEmpty()) {
            return;
        }
        for (MissionGarde g : gardes()) {
            if ((g.type() != MissionGarde.GARDE && g.type() != MissionGarde.ARCHER) || g.assomme()) {
                continue;
            }
            ServerPlayer proche = cs.get(0);
            for (ServerPlayer c : cs) {
                if (g.distanceToSqr(c) < g.distanceToSqr(proche)) {
                    proche = c;
                }
            }
            g.reperer(level, this, proche);
        }
    }

    // ================================================================== la cellule

    /** À deux cœurs : attrapé ! Direction la cellule, et ce qu'il avait sur lui retourne à sa place. */
    void capturer(ServerPlayer j) {
        captures.add(j.getUUID());
        reprendreObjets(j);
        MissionGarde o = otageEntite();
        if (o != null && j.getUUID().equals(o.suit)) {
            o.enfermer();
        }
        int n = captures.size() - 1;
        Vec3 p = scene.dedans.add((n % 3 - 1) * 0.6, 0, (n / 3) * 0.6);
        j.stopRiding();
        j.teleportTo(level, p.x, p.y, p.z, j.getYRot(), 0F);
        j.setHealth(j.getMaxHealth());
        j.clearFire();
        j.setAirSupply(j.getMaxAirSupply());
        j.fallDistance = 0;
        level.playSound(null, p.x, p.y, p.z, SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 1F, 0.8F);
        j.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
        j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Tes potes peuvent t'ouvrir : ").append(Component.keybind("key.use"))
                .append(" sur les barreaux.").withStyle(ChatFormatting.YELLOW)));
        j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("ATTRAPÉ !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        for (ServerPlayer a : participants()) {
            if (a != j) {
                a.sendSystemMessage(Component.literal(j.getGameProfile().getName() + " s'est fait attraper ! Il est en cellule.").withStyle(ChatFormatting.RED));
            }
        }
        if (!heros.isEmpty() && captures.containsAll(heros)) {
            for (ServerPlayer a : participants()) {
                a.displayClientMessage(Component.literal("Tout le monde est en cellule…").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true);
            }
        }
    }

    /** Un clic droit sur les barreaux (ou /mission liberer) : tout le monde sort de la cellule ; l'otage suit celui qui l'a libéré. */
    void liberer(ServerPlayer par) {
        int n = 0;
        for (UUID u : List.copyOf(captures)) {
            ServerPlayer j = level.getServer().getPlayerList().getPlayer(u);
            captures.remove(u);
            if (j != null && j.level() == level) {
                j.teleportTo(level, scene.dehors.x + (n % 3 - 1) * 0.7, scene.dehors.y, scene.dehors.z, j.getYRot(), 0F);
                j.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
                j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
                j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LIBÉRÉ !").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                n++;
            }
        }
        MissionGarde o = otageEntite();
        boolean otageLibere = o != null && !o.libere && par != null && estHeros(par);
        if (otageLibere) {
            o.libere = true;
            o.suit = par.getUUID();
            o.teleportTo(scene.dehors.x, scene.dehors.y, scene.dehors.z);
            for (ServerPlayer a : participants()) {
                a.sendSystemMessage(Component.literal("L'otage est libre ! Il suit " + par.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN));
            }
        }
        if (n > 0 || otageLibere) {
            level.playSound(null, scene.dehors.x, scene.dehors.y, scene.dehors.z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1F, 1F);
        } else if (par != null) {
            par.displayClientMessage(Component.literal("Il n'y a personne dans la cellule.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    boolean dansLaCellule(BlockPos p) {
        return scene.grille.contains(Vec3.atCenterOf(p));
    }

    private MissionGarde otageEntite() {
        return otage != null && level.getEntity(otage) instanceof MissionGarde g ? g : null;
    }

    // ================================================================== les objectifs

    private Scene.Objet objet(String id) {
        return scene.objets.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    /** L'objectif posé à sa place : il flotte, il brille. */
    private void poser(Scene.Objet o) {
        Display.ItemDisplay d = EntityType.ITEM_DISPLAY.create(level);
        if (d == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        d.saveWithoutId(tag);
        tag.put("item", pile(o).save(level.registryAccess()));
        tag.putString("billboard", "vertical");
        tag.putString("item_display", "ground");
        tag.putInt("glow_color_override", 0xFFD84A);
        d.load(tag);
        d.setGlowingTag(true);
        d.moveTo(o.p().x, o.p().y + 0.15, o.p().z, 0F, 0F);
        d.addTag(scene.tag());
        d.addTag(scene.tag(numero));
        level.addFreshEntity(d);
        poses.put(o.id(), d.getUUID());
    }

    /** La pile de l'objectif, marquée de la mission (on la reconnaît dans les poches). */
    ItemStack pile(Scene.Objet o) {
        ItemStack s = new ItemStack(Missions.objectif(o.item()));
        CompoundTag t = new CompoundTag();
        t.putString("scene", scene.id);
        t.putString("objet", o.id());
        t.putInt("numero", numero);
        CompoundTag racine = new CompoundTag();
        racine.put(CLE, t);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(racine));
        return s;
    }

    /** L'objet {@code id} de cette mission-ci ? */
    private boolean est(ItemStack s, String id) {
        CustomData c = s.get(DataComponents.CUSTOM_DATA);
        if (c == null || !c.contains(CLE)) {
            return false;
        }
        CompoundTag t = c.copyTag().getCompound(CLE);
        return t.getString("scene").equals(scene.id) && t.getString("objet").equals(id) && t.getInt("numero") == numero;
    }

    /** Qui a l'objet {@code id} sur lui (null : personne). */
    private ServerPlayer porteur(String id) {
        for (UUID u : concat()) {
            ServerPlayer j = level.getServer().getPlayerList().getPlayer(u);
            if (j == null) {
                continue;
            }
            for (ItemStack s : j.getInventory().items) {
                if (est(s, id)) {
                    return j;
                }
            }
            if (est(j.getOffhandItem(), id) || est(j.containerMenu.getCarried(), id)) {
                return j;
            }
        }
        return null;
    }

    /** Attrapé (ou parti) avec un objectif : il le perd, l'objectif retourne à sa place. */
    void reprendreObjets(ServerPlayer j) {
        for (Scene.Objet o : scene.objets) {
            boolean avait = false;
            for (int i = 0; i < j.getInventory().getContainerSize(); i++) {
                if (est(j.getInventory().getItem(i), o.id())) {
                    j.getInventory().setItem(i, ItemStack.EMPTY);
                    avait = true;
                }
            }
            if (est(j.containerMenu.getCarried(), o.id())) {
                j.containerMenu.setCarried(ItemStack.EMPTY);
                avait = true;
            }
            if (avait && !poses.containsKey(o.id())) {
                poser(o);
            }
        }
    }

    private void objectifs() {
        // ramasser : on passe dessus
        for (Scene.Objet o : scene.objets) {
            UUID u = poses.get(o.id());
            if (u == null) {
                continue;
            }
            Entity d = level.getEntity(u);
            if (d == null) {
                continue;
            }
            if (level.getGameTime() % 10 == 0) {
                level.sendParticles(ParticleTypes.WAX_ON, d.getX(), d.getY() + 0.3, d.getZ(), 2, 0.25, 0.25, 0.25, 0.4);
            }
            for (ServerPlayer h : cibles(level)) {
                if (h.position().add(0, 0.6, 0).distanceTo(d.position()) < 1.6) {
                    d.discard();
                    poses.remove(o.id());
                    if (!h.getInventory().add(pile(o))) {
                        h.drop(pile(o), false);
                    }
                    level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 1.3F);
                    for (ServerPlayer a : participants()) {
                        a.sendSystemMessage(Component.literal(h.getGameProfile().getName() + " a pris : ").withStyle(ChatFormatting.GOLD)
                                .append(pile(o).getHoverName().copy().withStyle(ChatFormatting.YELLOW)));
                    }
                    break;
                }
            }
        }
        // perdu (tombé dans le vide, jeté dans la lave…) : il retourne à sa place
        if (level.getGameTime() % 20 == 0) {
            for (Scene.Objet o : scene.objets) {
                if (poses.containsKey(o.id()) || porteur(o.id()) != null) {
                    continue;
                }
                boolean parTerre = !level.getEntitiesOfClass(ItemEntity.class, scene.etendue(), e -> est(e.getItem(), o.id())).isEmpty();
                if (!parTerre) {
                    poser(o);
                }
            }
        }
    }

    // ================================================================== à chaque tick

    void tick() {
        long t = level.getGameTime();
        boolean present = !cibles(level).isEmpty();
        if (present && restant > 0 && !reussie && !ratee && !toutSabote()) {
            if (--restant == 0) {
                rater();
            }
        }
        objectifs();
        if (t % 2 == 0) {
            lasers(t);
            pieges(t);
        }
        if (t % 5 == 0) {
            yeux(t);
        }
        sabotage();
        if (t % 10 == 0) {
            if (!reussie && reussite()) {
                reussir();
            }
            hud(t);
        }
    }

    // ---------------------------------------------------------------- les lasers (qui clignotent)

    private boolean laserAllume(int i, long t) {
        return (t + i * 37L) % 140 < 100;
    }

    private void lasers(long t) {
        for (int i = 0; i < scene.lasers.size(); i++) {
            if (!laserAllume(i, t)) {
                continue;
            }
            Vec3 a = scene.lasers.get(i)[0], b = scene.lasers.get(i)[1];
            if (t % 6 == 0) {
                Vec3 d = b.subtract(a);
                double l = d.length();
                for (double k = 0; k < l; k += 0.5) {
                    Vec3 p = a.add(d.scale(k / l));
                    level.sendParticles(ROUGE, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
            for (ServerPlayer h : cibles(level)) {
                AABB box = h.getBoundingBox();
                if (box.contains(a) || box.clip(a, b).isPresent()) {
                    level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 1.5F, 0.6F);
                    alarme(level, h.position(), h, false);
                }
            }
        }
    }

    // ---------------------------------------------------------------- les caméras, les projecteurs

    private void yeux(long t) {
        for (int i = 0; i < scene.cameras.size(); i++) {
            Scene.Oeil c = scene.cameras.get(i);
            float lacet = c.lacet() + (float) (c.balayage() * Math.sin(t * Math.PI * 2 / 160 + i));
            if (t % 20 == 0) {
                level.sendParticles(ROUGE, c.p().x, c.p().y - 0.2, c.p().z, 1, 0, 0, 0, 0);
            }
            regarder(c, lacet, false);
        }
        for (int i = 0; i < scene.projecteurs.size(); i++) {
            Scene.Oeil c = scene.projecteurs.get(i);
            float lacet = c.lacet() + (float) (c.balayage() * Math.sin(t * Math.PI * 2 / 220 + i * 2));
            // le faisceau : de la lampe jusqu'au sable, et la tache de lumière par terre
            Vec3 dir = Vec3.directionFromRotation(0, lacet);
            Vec3 sol = new Vec3(c.p().x + dir.x * c.portee() * 0.6, Scene.SOL + 0.2, c.p().z + dir.z * c.portee() * 0.6);
            Vec3 d = sol.subtract(c.p());
            for (int k = 1; k <= 10; k++) {
                Vec3 p = c.p().add(d.scale(k / 10.0));
                level.sendParticles(FAISCEAU, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
            }
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                level.sendParticles(FAISCEAU, sol.x + Math.cos(a) * 1.6, sol.y, sol.z + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
            }
            regarder(c, lacet, true);
        }
    }

    private void regarder(Scene.Oeil c, float lacet, boolean projecteur) {
        Vec3 dir = Vec3.directionFromRotation(0, lacet);
        double cos = Math.cos(Math.toRadians(c.angle()));
        for (ServerPlayer h : cibles(level)) {
            double dx = h.getX() - c.p().x, dz = h.getZ() - c.p().z, hd = Math.sqrt(dx * dx + dz * dz);
            if (hd > c.portee() || hd < 0.5 || (dir.x * dx + dir.z * dz) / hd < cos || Missions.cache(level, h)) {
                continue;
            }
            if (!projecteur && h.isCrouching() && hd > c.portee() * 0.6) {
                continue;
            }
            if (Missions.voit(level, c.p(), h, null)) {
                if (projecteur) {
                    level.sendParticles(ParticleTypes.FLASH, h.getX(), h.getY() + 1, h.getZ(), 1, 0, 0, 0, 0);
                }
                alarme(level, h.position(), h, false);
            }
        }
    }

    // ---------------------------------------------------------------- les pièges de la forêt

    private void pieges(long t) {
        for (int i = 0; i < scene.pieges.size(); i++) {
            Scene.Piege p = scene.pieges.get(i);
            if (pieges[i] != 0) {
                if (p.type().equals("tronc") && t - pieges[i] == 10) {
                    for (ServerPlayer h : cibles(level)) {
                        if (p.frappe().intersects(h.getBoundingBox())) {
                            h.hurt(level.damageSources().fallingBlock(h), 6F);
                        }
                    }
                }
                if (t - pieges[i] > 300 && rearmer(p)) {
                    pieges[i] = 0;
                }
                continue;
            }
            for (ServerPlayer h : cibles(level)) {
                AABB pieds = new AABB(h.getX() - 0.3, h.getY(), h.getZ() - 0.3, h.getX() + 0.3, h.getY() + 0.5, h.getZ() + 0.3);
                if (!p.zone().intersects(pieds)) {
                    continue;
                }
                pieges[i] = t;
                declencher(p, h);
                break;
            }
        }
    }

    private void declencher(Scene.Piege p, ServerPlayer h) {
        switch (p.type()) {
            case "fil" -> {
                level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.BLOCKS, 1.5F, 1F);
                // deux flèches, de chaque côté du sentier
                for (int s : new int[] {-1, 1}) {
                    Vec3 de = new Vec3(p.zone().getCenter().x + s * 4, h.getY() + 1.4, h.getZ());
                    Arrow f = new Arrow(level, de.x, de.y, de.z, new ItemStack(Items.ARROW), null);
                    Vec3 d = h.position().add(0, 1, 0).subtract(de);
                    f.shoot(d.x, d.y, d.z, 1.6F, 2F);
                    f.setBaseDamage(1.5);
                    f.pickup = AbstractArrow.Pickup.DISALLOWED;
                    f.addTag(Missions.FLECHE);
                    level.addFreshEntity(f);
                }
                level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.BLOCKS, 1.2F, 0.9F);
            }
            case "trou" -> {
                int[] b = p.boite();
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        BlockPos q = scene.bloc(x, b[1], z);
                        level.destroyBlock(q, false);
                    }
                }
                level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 1.5F, 0.7F);
            }
            case "tronc" -> {
                int[] b = p.boiteTronc();
                for (int x = b[0]; x <= b[3]; x++) {
                    BlockPos q = scene.bloc(x, b[1], b[2]);
                    BlockState s = level.getBlockState(q);
                    if (!s.isAir()) {
                        FallingBlockEntity f = FallingBlockEntity.fall(level, q, s);
                        f.disableDrop();
                    }
                }
                level.playSound(null, h.getX(), h.getY() + 5, h.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2F, 0.7F);
                level.playSound(null, h.getX(), h.getY(), h.getZ(), SoundEvents.HEAVY_CORE_PLACE, SoundSource.BLOCKS, 1F, 0.6F);
            }
            default -> {
            }
        }
        alarme(level, h.position(), h, false);
    }

    /** Le piège se remet en place (pas tant que quelqu'un est dedans). */
    private boolean rearmer(Scene.Piege p) {
        switch (p.type()) {
            case "trou" -> {
                AABB trou = p.zone().expandTowards(0, -3, 0);
                for (ServerPlayer j : participants()) {
                    if (trou.intersects(j.getBoundingBox())) {
                        return false;
                    }
                }
                int[] b = p.boite();
                BlockState couvercle = Blocks.DIRT_PATH.defaultBlockState();
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        level.setBlock(scene.bloc(x, b[1], z), couvercle, 2);
                    }
                }
            }
            case "tronc" -> {
                int[] b = p.boiteTronc();
                BlockState tronc = Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        net.minecraft.core.Direction.Axis.X);
                for (int x = b[0]; x <= b[3]; x++) {
                    level.setBlock(scene.bloc(x, b[1], b[2]), tronc, 2);
                }
            }
            default -> {
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- les réservoirs de la fusée

    int reservoir(BlockPos p) {
        for (int i = 0; i < scene.reservoirs.size(); i++) {
            if (scene.reservoirs.get(i).contains(Vec3.atCenterOf(p))) {
                return i;
            }
        }
        return -1;
    }

    void commencerSabotage(ServerPlayer j, int i) {
        if (sabotes[i]) {
            j.displayClientMessage(Component.literal("Ce réservoir est déjà saboté.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (saboteur != null && !saboteur.equals(j.getUUID())) {
            return;
        }
        saboteur = j.getUUID();
        saboteurReservoir = i;
        saboteurTicks = 0;
    }

    private void sabotage() {
        if (saboteur == null) {
            return;
        }
        ServerPlayer j = cible(level, saboteur);
        Vec3 c = scene.reservoirs.get(saboteurReservoir).getCenter();
        if (j == null || j.position().distanceTo(c) > 4) {
            if (j != null) {
                j.displayClientMessage(Component.literal("Sabotage interrompu !").withStyle(ChatFormatting.RED), true);
            }
            saboteur = null;
            return;
        }
        saboteurTicks++;
        int n = saboteurTicks * 10 / 60;
        j.displayClientMessage(Component.literal("Sabotage… " + "▮".repeat(n) + "▯".repeat(10 - n)).withStyle(ChatFormatting.GOLD), true);
        if (saboteurTicks % 8 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 8, 0.4, 0.6, 0.4, 0.3);
            level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.4F, 1.8F);
        }
        if (saboteurTicks < 60) {
            return;
        }
        sabotes[saboteurReservoir] = true;
        saboteur = null;
        level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.5, 0.5, 0.5, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 1, c.z, 30, 0.4, 1, 0.4, 0.05);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.2F, 1.4F);
        int faits = nombreSabotes();
        for (ServerPlayer a : participants()) {
            a.sendSystemMessage(Component.literal("Réservoir saboté (" + faits + "/" + sabotes.length + ") par " + j.getGameProfile().getName() + " !")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (toutSabote()) {
            for (ServerPlayer a : participants()) {
                a.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
                a.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("File au sous-marin, sur la plage ouest !").withStyle(ChatFormatting.YELLOW)));
                a.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LA FUSÉE EST SABOTÉE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            }
            derniereAlarme = 0;
            alarme(level, c, j, true);
        }
    }

    private int nombreSabotes() {
        int n = 0;
        for (boolean b : sabotes) {
            n += b ? 1 : 0;
        }
        return n;
    }

    private boolean toutSabote() {
        return sabotes.length > 0 && nombreSabotes() == sabotes.length;
    }

    // ---------------------------------------------------------------- la fin

    private boolean dansLaSortie(ServerPlayer j) {
        return scene.sortie.contains(j.position()) || scene.sortie.intersects(j.getBoundingBox());
    }

    private boolean reussite() {
        if (!scene.objets.isEmpty()) {
            for (Scene.Objet o : scene.objets) {
                ServerPlayer p = porteur(o.id());
                if (p == null || !estCible(p) || !dansLaSortie(p)) {
                    return false;
                }
            }
            return true;
        }
        if (sabotes.length > 0) {
            return toutSabote() && cibles(level).stream().anyMatch(this::dansLaSortie);
        }
        MissionGarde o = otageEntite();
        return o != null && o.libere && scene.sortie.intersects(o.getBoundingBox());
    }

    private void reussir() {
        reussie = true;
        for (ServerPlayer j : participants()) {
            j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 30));
            j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Bravo ! /mission retour pour rentrer.").withStyle(ChatFormatting.YELLOW)));
            j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("MISSION ACCOMPLIE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            j.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
        }
        Vec3 c = scene.sortie.getCenter();
        level.sendParticles(ParticleTypes.FIREWORK, c.x, c.y + 1, c.z, 120, 2, 2, 2, 0.15);
        for (MissionGarde g : gardes()) {
            g.retour();
        }
    }

    private void rater() {
        ratee = true;
        for (ServerPlayer j : participants()) {
            j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 30));
            j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("La fusée a décollé… Mission ratée.").withStyle(ChatFormatting.GRAY)));
            j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("TROP TARD !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }
        if (!scene.reservoirs.isEmpty()) {
            Vec3 c = scene.reservoirs.get(0).getCenter();
            Vec3 fusee = new Vec3(scene.ox - 7.5, Scene.SOL + 5, scene.oz - 7.5);
            for (int k = 0; k < 30; k++) {
                level.sendParticles(ParticleTypes.FLAME, fusee.x, fusee.y + k, fusee.z, 6, 0.6, 0.3, 0.6, 0.05);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, fusee.x, fusee.y + k, fusee.z, 3, 0.8, 0.3, 0.8, 0.02);
            }
            level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 6F, 0.5F);
            level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4F, 0.6F);
        }
    }

    // ---------------------------------------------------------------- le panneau en haut à droite

    private static final Map<String, String> NOMS = Map.of("formule", "la formule", "rouleau", "le rouleau", "pacte", "le pacte");

    private List<String> lignes() {
        List<String> l = new ArrayList<>();
        if (reussie) {
            l.add("§6§lMISSION ACCOMPLIE");
            l.add("§7/mission retour pour rentrer");
            return l;
        }
        if (ratee) {
            l.add("§c§lMISSION RATÉE");
        }
        switch (scene.id) {
            case "whoop1" -> objetUnique(l, "formule", "Vole la formule (3e étage)", "Ramène-la sur le toit");
            case "whoop2" -> {
                int n = 0;
                for (Scene.Objet o : scene.objets) {
                    n += poses.containsKey(o.id()) ? 0 : 1;
                }
                l.add((n == 3 ? "§a✔ " : "§f• ") + "Puces trouvées : " + n + "/3");
                l.add("§f• Ramène-les sur le toit");
            }
            case "whoop3" -> {
                int n = nombreSabotes();
                l.add((n == sabotes.length ? "§a✔ " : "§f• ") + "Réservoirs sabotés : " + n + "/" + sabotes.length);
                l.add("§f• Puis file au sous-marin (plage ouest)");
                if (!toutSabote() && !ratee) {
                    int s = (restant + 19) / 20;
                    l.add((s < 120 ? "§c" : "§e") + "Décollage dans " + s / 60 + ":" + String.format("%02d", s % 60));
                }
            }
            case "anbu1" -> objetUnique(l, "rouleau", "Vole le rouleau (tour du chef)", "Sors par le torii, au nord");
            case "anbu2" -> {
                MissionGarde o = otageEntite();
                ServerPlayer s = o == null || o.suit == null ? null : level.getServer().getPlayerList().getPlayer(o.suit);
                l.add(o != null && o.libere ? "§a✔ L'otage suit " + (s == null ? "…" : s.getGameProfile().getName())
                        : "§f• Libère l'otage (camp des déserteurs)");
                l.add("§f• Ramène-le au début du sentier");
            }
            case "anbu3" -> objetUnique(l, "pacte", "Vole le pacte (donjon, 2e étage)", "Sors par le toit du donjon");
            default -> {
            }
        }
        if (alarmeActive(level)) {
            l.add("§c§l⚠ ALARME");
        }
        List<String> noms = new ArrayList<>();
        for (UUID u : captures) {
            ServerPlayer j = level.getServer().getPlayerList().getPlayer(u);
            if (j != null) {
                noms.add(j.getGameProfile().getName());
            }
        }
        if (!noms.isEmpty()) {
            l.add("§7En cellule : " + String.join(", ", noms));
        }
        return l;
    }

    private void objetUnique(List<String> l, String id, String prendre, String sortir) {
        Scene.Objet o = objet(id);
        ServerPlayer p = o == null ? null : porteur(id);
        if (p != null) {
            l.add("§a✔ " + p.getGameProfile().getName() + " a " + NOMS.getOrDefault(id, id));
        } else {
            l.add("§f• " + prendre);
        }
        l.add("§f• " + sortir);
    }

    private void hud(long t) {
        List<String> l = lignes();
        String cle = String.join("\n", l);
        for (ServerPlayer j : participants()) {
            if (!Objects.equals(hudEnvoye.get(j.getUUID()), cle) || t % 100 == 0) {
                hudEnvoye.put(j.getUUID(), cle);
                PacketDistributor.sendToPlayer(j, new Missions.Hud(scene.titre, l, scene.brume));
            }
        }
    }

    /** Le mode d'avant (pour savoir si on est en survie : seul ce mode passe en aventure). */
    static boolean survie(ServerPlayer j) {
        return j.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    static float lacet(Vec3 de, Vec3 a) {
        return (float) (Mth.atan2(a.z - de.z, a.x - de.x) * Mth.RAD_TO_DEG) - 90F;
    }
}
