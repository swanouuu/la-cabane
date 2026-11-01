package fr.cabane.bloc;

import fr.cabane.CabaneBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * La mémoire d'un bloc interactif : qui l'a posé, et pour les jeux du casino, la caisse. Le gérant
 * remplit la caisse, les gains des joueurs en sortent, les mises y entrent ; il récupère le tout.
 */
public class InteractifBlockEntity extends BlockEntity {
    private UUID proprietaire;
    private String nomProprietaire = "";
    private long caisse;

    // ce que montrent les grandes tables : le dernier tirage (roulette, roue), les mains (blackjack)
    private int tirage = -1;
    private int precedent = -1;
    private long debut = -1000;
    private String joueur = "";
    private long gain;
    private List<Place> places = List.of();

    /** Un joueur assis au blackjack : sa main, celle de la banque en face de lui, où en est la partie. */
    public record Place(int siege, String nom, List<Integer> cartes, List<Integer> banque, boolean cache, int etat, long mise, long gain, long fin) {
    }

    public InteractifBlockEntity(BlockPos pos, BlockState state) {
        super(CabaneBlocks.INTERACTIF_BE.get(), pos, state);
    }

    public UUID proprietaire() {
        return proprietaire;
    }

    public String nomProprietaire() {
        return nomProprietaire;
    }

    public boolean estProprietaire(UUID id) {
        return id.equals(proprietaire);
    }

    public void definirProprietaire(UUID id, String nom) {
        proprietaire = id;
        nomProprietaire = nom;
        modifie();
    }

    public long caisse() {
        return caisse;
    }

    public void ajouterCaisse(long montant) {
        caisse = Math.max(0, caisse + montant);
        modifie();
    }

    /** Un tirage de roulette ou de roue : tout le casino le voit tourner. */
    public void annoncer(int tirage, String joueur, long gain) {
        precedent = this.tirage;
        this.tirage = tirage;
        this.joueur = joueur;
        this.gain = gain;
        debut = level == null ? 0 : level.getGameTime();
        modifie();
    }

    public boolean tourne(long maintenant, int duree) {
        return maintenant - debut < duree;
    }

    public int tirage() {
        return tirage;
    }

    public int precedent() {
        return precedent;
    }

    public long debut() {
        return debut;
    }

    public String joueur() {
        return joueur;
    }

    public long gain() {
        return gain;
    }

    public List<Place> places() {
        return places;
    }

    public void places(List<Place> places) {
        this.places = List.copyOf(places);
        modifie();
    }

    private void modifie() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (proprietaire != null) {
            tag.putUUID("proprietaire", proprietaire);
        }
        tag.putString("nom", nomProprietaire);
        tag.putLong("caisse", caisse);
        tag.putInt("tirage", tirage);
        tag.putInt("precedent", precedent);
        tag.putLong("debut", debut);
        tag.putString("joueur", joueur);
        tag.putLong("gain", gain);
        ListTag liste = new ListTag();
        for (Place p : places) {
            CompoundTag t = new CompoundTag();
            t.putInt("siege", p.siege());
            t.putString("nom", p.nom());
            t.putIntArray("cartes", p.cartes());
            t.putIntArray("banque", p.banque());
            t.putBoolean("cache", p.cache());
            t.putInt("etat", p.etat());
            t.putLong("mise", p.mise());
            t.putLong("gain", p.gain());
            t.putLong("fin", p.fin());
            liste.add(t);
        }
        tag.put("places", liste);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        proprietaire = tag.hasUUID("proprietaire") ? tag.getUUID("proprietaire") : null;
        nomProprietaire = tag.getString("nom");
        caisse = tag.getLong("caisse");
        tirage = tag.contains("tirage") ? tag.getInt("tirage") : -1;
        precedent = tag.contains("precedent") ? tag.getInt("precedent") : -1;
        debut = tag.contains("debut") ? tag.getLong("debut") : -1000;
        joueur = tag.getString("joueur");
        gain = tag.getLong("gain");
        List<Place> liste = new ArrayList<>();
        for (Tag e : tag.getList("places", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            liste.add(new Place(t.getInt("siege"), t.getString("nom"), entiers(t.getIntArray("cartes")), entiers(t.getIntArray("banque")),
                    t.getBoolean("cache"), t.getInt("etat"), t.getLong("mise"), t.getLong("gain"), t.getLong("fin")));
        }
        places = List.copyOf(liste);
    }

    private static List<Integer> entiers(int[] t) {
        List<Integer> l = new ArrayList<>(t.length);
        for (int v : t) {
            l.add(v);
        }
        return l;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
