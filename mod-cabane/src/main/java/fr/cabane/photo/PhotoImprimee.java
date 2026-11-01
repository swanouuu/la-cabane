package fr.cabane.photo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Une photo imprimée : quelle photo du serveur, et sa taille sur le mur (en blocs). */
public record PhotoImprimee(long id, int largeur, int hauteur) {
    public static final Codec<PhotoImprimee> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("id").forGetter(PhotoImprimee::id),
            Codec.INT.fieldOf("largeur").forGetter(PhotoImprimee::largeur),
            Codec.INT.fieldOf("hauteur").forGetter(PhotoImprimee::hauteur)
    ).apply(i, PhotoImprimee::new));

    public static final StreamCodec<ByteBuf, PhotoImprimee> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, PhotoImprimee::id,
            ByteBufCodecs.VAR_INT, PhotoImprimee::largeur,
            ByteBufCodecs.VAR_INT, PhotoImprimee::hauteur,
            PhotoImprimee::new);
}
