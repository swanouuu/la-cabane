package fr.cabane.client;

import fr.cabane.pouvoir.Corbeau;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.theme.IThemeHelper;

/**
 * Ce que Jade affiche en haut de l'écran quand on regarde quelqu'un : un joueur en corbeau n'est qu'un « Corbeau »,
 * ni son pseudo ni son armure, sinon on le reconnaît.
 */
@WailaPlugin
public class JadeCabane implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // caché dans le Kamui : Jade ne le voit pas du tout
        registration.addRayTraceCallback((visee, cible, original) -> cible instanceof EntityAccessor e
                && fr.cabane.client.pouvoir.ClientKamui.cache(e.getEntity().getUUID()) ? null : cible);
        registration.addTooltipCollectedCallback((boite, cible) -> {
            if (cible instanceof EntityAccessor e && e.getEntity() instanceof Player joueur && Corbeau.actif(joueur.getUUID())) {
                ITooltip bulle = boite.getTooltip();
                bulle.replace(JadeIds.CORE_OBJECT_NAME, IThemeHelper.get().title(Component.literal("Corbeau")));
                bulle.remove(JadeIds.MC_ENTITY_ARMOR);
            }
            // Ugrak : sa vraie vie (1 000) n'est pas celle de sa barre (4 000) ; la barre de boss suffit
            if (cible instanceof EntityAccessor e && e.getEntity() instanceof fr.cabane.donjon.Ogre) {
                boite.getTooltip().remove(JadeIds.MC_ENTITY_HEALTH);
                boite.getTooltip().remove(JadeIds.MC_ENTITY_ARMOR);
            }
        });
    }
}
