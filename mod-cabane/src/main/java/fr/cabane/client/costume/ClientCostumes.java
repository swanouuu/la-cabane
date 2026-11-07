package fr.cabane.client.costume;

import fr.cabane.Cabane;
import fr.cabane.costume.Costumes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.Player;

/** Les skins des méchants (/skin), côté jeu : la peau du joueur costumé, et son pseudo rafraîchi dès que ça change. */
public final class ClientCostumes {
    private static final Map<String, PlayerSkin> CACHE = new HashMap<>();

    public static void recevoir(Map<UUID, String> portes) {
        Costumes.CLIENT.clear();
        Costumes.CLIENT.putAll(portes);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Player p : mc.level.players()) {
                p.refreshDisplayName();
            }
        }
    }

    /** La peau du costume porté (la cape et les élytres du joueur restent). */
    public static PlayerSkin peau(AbstractClientPlayer p, PlayerSkin skin) {
        String id = Costumes.CLIENT.get(p.getUUID());
        Costumes.Costume c = id == null ? null : Costumes.TOUS.get(id);
        if (c == null) {
            return skin;
        }
        PlayerSkin base = CACHE.computeIfAbsent(id, k -> new PlayerSkin(Cabane.id("textures/entity/costume/" + k + ".png"), null, null, null,
                c.fin() ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, true));
        return skin.capeTexture() == null && skin.elytraTexture() == null ? base
                : new PlayerSkin(base.texture(), null, skin.capeTexture(), skin.elytraTexture(), base.model(), true);
    }

    private ClientCostumes() {
    }
}
