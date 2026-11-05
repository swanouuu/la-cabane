package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les géants qu'un joueur pilote (le Hachibi entier de Hachibi 4, le Susano parfait) : le joueur reste de taille
 * normale au creux du géant, alors on le touche sur toute la taille du géant. Un coup (clic gauche) qui atteint la
 * boîte du géant part sur le joueur dedans ({@link fr.cabane.client.pouvoir.ClientGeants} le vise, le serveur le
 * vérifie), et une flèche qui y entre aussi. Il prend les coups avec son armure de géant : il reste très résistant.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Geants {
    public static final int AUCUN = 0, HACHIBI = 1, SUSANO = 2;

    /** La boîte du géant, selon où est le joueur : le Hachibi debout autour de lui, le Susano sous lui (il est dans sa tête). */
    public static AABB boite(Vec3 p, int sorte) {
        return switch (sorte) {
            case HACHIBI -> new AABB(p.x - 9, p.y, p.z - 9, p.x + 9, p.y + 22, p.z + 9);
            case SUSANO -> new AABB(p.x - 6, p.y - Susano4.HAUT, p.z - 6, p.x + 6, p.y - Susano4.HAUT + 29, p.z + 6);
            default -> null;
        };
    }

    static int sorte(ServerPlayer joueur) {
        if (Hachibi4.actif(joueur)) {
            return HACHIBI;
        }
        return Susano4.enForme(joueur) ? SUSANO : AUCUN;
    }

    /** Client → serveur : il a frappé le géant de ce joueur. */
    public record Frappe(int cible) implements CustomPacketPayload {
        public static final Type<Frappe> TYPE = new Type<>(Cabane.id("geant_frappe"));
        public static final StreamCodec<ByteBuf, Frappe> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Frappe::cible, Frappe::new);

        @Override
        public Type<Frappe> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Frappe.TYPE, Frappe.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer a) {
                frapper(a, p.cible());
            }
        }));
    }

    /** Le coup part sur le joueur dans le géant, s'il est vraiment à portée de la boîte (comme un coup normal). */
    private static void frapper(ServerPlayer a, int cible) {
        if (!a.isAlive() || a.isSpectator() || !(a.serverLevel().getEntity(cible) instanceof ServerPlayer g) || g == a) {
            return;
        }
        AABB b = boite(g.position(), sorte(g));
        if (b == null) {
            return;
        }
        double portee = a.entityInteractionRange() + 2;
        if (distance2(b, a.getEyePosition()) <= portee * portee) {
            a.attack(g);
        }
    }

    private static double distance2(AABB b, Vec3 p) {
        double dx = Math.max(0, Math.max(b.minX - p.x, p.x - b.maxX));
        double dy = Math.max(0, Math.max(b.minY - p.y, p.y - b.maxY));
        double dz = Math.max(0, Math.max(b.minZ - p.z, p.z - b.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Les flèches (et tridents) qui entrent dans un géant le touchent. */
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        for (ServerPlayer g : event.getServer().getPlayerList().getPlayers()) {
            int sorte = sorte(g);
            if (sorte == AUCUN) {
                continue;
            }
            ServerLevel monde = g.serverLevel();
            AABB b = boite(g.position(), sorte);
            for (AbstractArrow f : monde.getEntitiesOfClass(AbstractArrow.class, b, x -> x.getOwner() != g && x.isAlive() && x.getDeltaMovement().lengthSqr() > 0.04)) {
                float degats = (float) Math.ceil(Math.max(2, f.getBaseDamage() * f.getDeltaMovement().length()));
                g.hurt(monde.damageSources().arrow(f, f.getOwner()), degats);
                f.discard();
            }
        }
    }

    private Geants() {
    }
}
