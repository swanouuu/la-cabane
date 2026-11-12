package fr.cabane.client.pouvoir;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier;
import dev.kosmx.playerAnim.core.data.AnimationFormat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/**
 * Les coups des lames joués par playerAnimator (la bibliothèque d'animation livrée avec Emotecraft, et bendy-lib) : les
 * coudes et les genoux se plient, le buste se penche et se tord, tout le corps peut tourner sur lui-même (les coups en
 * toupie) ou descendre dans la frappe. Sans Emotecraft, {@link ClientLames} fait les gestes lui-même (bras raides).
 * Seule cette classe touche à la bibliothèque : on ne l'appelle que si elle est là ({@link #dispo()}).
 */
final class AnimLames {
    static final ResourceLocation ID = Cabane.id("lames");
    private static boolean pret;
    /** Les animations déjà construites, par coup (et par tick de départ). */
    private static final Map<String, KeyframeAnimation> FAITES = new HashMap<>();

    static boolean dispo() {
        return ModList.get().isLoaded("playeranimator");
    }

    /** Une couche d'animation par joueur, au-dessus des émotes. */
    static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ID, 1500, p -> new ModifierLayer<>());
        pret = true;
    }

    private static Ease ease(String e) {
        return switch (e) {
            case "lin" -> Ease.LINEAR;
            case "sortie" -> Ease.OUTCUBIC;
            case "entree" -> Ease.INCUBIC;
            default -> Ease.INOUTSINE;
        };
    }

    /**
     * Le coup en animation (les clés de combos.json, depuis le tick {@code depuis}) : les bras, les coudes, les jambes,
     * les genoux, le buste plié à la taille ; « body » est le personnage entier (il tourne sur lui-même, descend, avance).
     * Comme dans l'aperçu, l'easing d'une clé est celui avec lequel on y arrive. Tenue : la dernière pose reste (la
     * charge de Hiramekarei) au lieu de revenir à la pose normale.
     */
    private static KeyframeAnimation construire(CombosLames.Coup c, int depuis, boolean tenue) {
        var b = new KeyframeAnimation.AnimationBuilder(AnimationFormat.JSON_EMOTECRAFT);
        b.isEasingBefore = true;
        int duree = c.duree() - depuis;
        b.beginTick = 0;
        b.endTick = duree;
        b.stopTick = tenue ? duree + 1 : duree + (c.tour() ? 2 : 5);
        b.isLooped = tenue;
        b.returnTick = Math.max(0, duree - 1);
        b.name = "lame";
        java.util.List<CombosLames.Cle> cles = new java.util.ArrayList<>();
        if (depuis > 0) {
            cles.add(new CombosLames.Cle(0, "lin", CombosLames.pose(c, depuis)));
        }
        for (CombosLames.Cle k : c.cles()) {
            if (k.t() > depuis || depuis == 0) {
                cles.add(new CombosLames.Cle(k.t() - depuis, k.ease(), k.v()));
            }
        }
        for (CombosLames.Cle k : cles) {
            int t = k.t();
            float[] v = k.v();
            Ease e = ease(k.ease());
            b.rightArm.pitch.addKeyFrame(t, v[CombosLames.BD], e);
            b.rightArm.yaw.addKeyFrame(t, v[CombosLames.BD + 1], e);
            b.rightArm.roll.addKeyFrame(t, v[CombosLames.BD + 2], e);
            b.leftArm.pitch.addKeyFrame(t, v[CombosLames.BG], e);
            b.leftArm.yaw.addKeyFrame(t, v[CombosLames.BG + 1], e);
            b.leftArm.roll.addKeyFrame(t, v[CombosLames.BG + 2], e);
            b.rightArm.bend.addKeyFrame(t, v[CombosLames.CD], e);
            b.leftArm.bend.addKeyFrame(t, v[CombosLames.CG], e);
            b.rightLeg.pitch.addKeyFrame(t, v[CombosLames.JD], e);
            b.leftLeg.pitch.addKeyFrame(t, v[CombosLames.JG], e);
            b.rightLeg.bend.addKeyFrame(t, v[CombosLames.GD], e);
            b.leftLeg.bend.addKeyFrame(t, v[CombosLames.GG], e);
            b.body.bend.addKeyFrame(t, v[CombosLames.PENCHE], e);
            // « body » : le personnage entier. Son repère : l'avant vers -z (d'où le signe d'« avant »).
            b.body.yaw.addKeyFrame(t, v[CombosLames.TOUR], e);
            b.body.y.addKeyFrame(t, v[CombosLames.HAUT] / 16F, e);
            b.body.z.addKeyFrame(t, -v[CombosLames.AVANT] / 16F, e);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> couche(Player j) {
        if (!pret || !(j instanceof AbstractClientPlayer p)) {
            return null;
        }
        return PlayerAnimationAccess.getPlayerAssociatedData(p).get(ID) instanceof ModifierLayer<?> m ? (ModifierLayer<IAnimation>) m : null;
    }

    /**
     * Joue ce coup sur ce joueur, depuis ce tick (en miroir pour un gaucher). Un tour complet commence net (sans fondu, il
     * partirait en vrille). Tenue : la dernière pose reste jusqu'au coup suivant ({@link #arreter}).
     */
    static void jouer(Player j, CombosLames.Coup c, int depuis, boolean tenue) {
        ModifierLayer<IAnimation> couche = couche(j);
        if (couche == null) {
            return;
        }
        KeyframeAnimation a = FAITES.computeIfAbsent(System.identityHashCode(c) + ":" + depuis + ":" + tenue, k -> construire(c, depuis, tenue));
        IAnimation lecteur = new KeyframeAnimationPlayer(a);
        if (j.getMainArm() == HumanoidArm.LEFT) {
            lecteur = new ModifierLayer<>(lecteur, new MirrorModifier(true));
        }
        if (c.tour() || depuis > 0) {
            couche.setAnimation(lecteur);
        } else {
            couche.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(2, Ease.INOUTSINE), lecteur);
        }
    }

    /** Fin de l'animation (en douceur). */
    static void arreter(Player j) {
        ModifierLayer<IAnimation> couche = couche(j);
        if (couche != null && couche.isActive()) {
            couche.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(4, Ease.INOUTSINE), null);
        }
    }

    /** Une animation de lame est en cours sur ce joueur. */
    static boolean actif(Player j) {
        ModifierLayer<IAnimation> couche = couche(j);
        return couche != null && couche.isActive();
    }

    /** Le pli du coude de ce bras (« rightArm », « leftArm ») : {axe, angle}, ou null s'il ne plie pas. */
    static float[] coude(Player j, String bras) {
        if (!pret || !(j instanceof IAnimatedPlayer a)) {
            return null;
        }
        AnimationApplier ap = a.playerAnimator_getAnimation();
        if (ap == null || !ap.isActive()) {
            return null;
        }
        Pair<Float, Float> p = ap.getBend(bras);
        return p == null ? null : new float[]{p.getLeft(), p.getRight()};
    }

    private AnimLames() {
    }
}
