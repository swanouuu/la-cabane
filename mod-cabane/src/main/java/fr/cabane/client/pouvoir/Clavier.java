package fr.cabane.client.pouvoir;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Les noms des touches dans les panneaux des pouvoirs : celles que ce joueur a choisies dans ses options (s'accroupir
 * n'est pas forcément Maj, frapper pas forcément le clic gauche), jamais des noms écrits en dur.
 */
public final class Clavier {
    public static String nom(KeyMapping touche) {
        return touche.getTranslatedKeyMessage().getString();
    }

    /** Frapper (d'habitude le clic gauche). */
    public static String gauche() {
        return nom(Minecraft.getInstance().options.keyAttack);
    }

    /** Utiliser (d'habitude le clic droit). */
    public static String droite() {
        return nom(Minecraft.getInstance().options.keyUse);
    }

    public static String accroupi() {
        return nom(Minecraft.getInstance().options.keyShift);
    }

    public static String saut() {
        return nom(Minecraft.getInstance().options.keyJump);
    }

    private Clavier() {
    }
}
