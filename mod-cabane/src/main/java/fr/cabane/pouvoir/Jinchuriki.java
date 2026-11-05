package fr.cabane.pouvoir;

import net.minecraft.server.level.ServerPlayer;

/** Ceux qui portent une bête en eux (Hachibi, Ichibi, Minato et son Kurama) : eux seuls peuvent se faire la marque du jinchûriki. */
public final class Jinchuriki {
    public static boolean porteUneBete(ServerPlayer joueur) {
        String pouvoir = Pouvoirs.pouvoir(joueur);
        return Hachibi.est(pouvoir) || Ichibi.est(pouvoir) || Minato.est(pouvoir);
    }

    private Jinchuriki() {
    }
}
