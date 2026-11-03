package fr.cabane.labo;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ce qui fait vivre le labo tant que quelqu'un y est : les lampes qui grésillent et s'éteignent, les arcs entre les
 * bobines Tesla, les bulles des cuves, les gouttes, les bruits lointains (des cris, des chaînes, quelque chose qui
 * frappe derrière la porte d'isolement) et les écrans de contrôle qui défilent.
 */
final class LaboVie {
    private static final Map<BlockPos, Integer> ETEINTES = new HashMap<>();
    private static List<Display.TextDisplay> ecrans = List.of();
    private static long rechercheEcrans = -1000;
    private static EntityDataAccessor<Component> texteEcran;
    private static boolean reflexionRatee;
    private static final Style MONO = Style.EMPTY.withFont(ResourceLocation.withDefaultNamespace("uniform"));
    private static final net.minecraft.core.particles.DustParticleOptions BRUME =
            new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.8F, 0.03F, 0.08F), 1.1F);

    static void tick(ServerLevel level) {
        long t = level.getServer().getTickCount();
        RandomSource r = level.random;
        if (t % 3 == 0) {
            lampes(level, r);
        }
        if (r.nextInt(90) == 0) {
            int i = r.nextInt(LaboBuilder.BOBINES.length);
            Vec3 cible = r.nextInt(3) == 0 ? Vec3.atCenterOf(LaboBuilder.abs(LaboBuilder.TABLE.getX(), LaboBuilder.TABLE.getY(), LaboBuilder.TABLE.getZ())).add(0, 0.4, 0)
                    : sommet(r.nextInt(LaboBuilder.BOBINES.length));
            arc(level, i, cible);
        }
        if (t % 5 == 0) {
            particules(level, r, t);
        }
        if (t % 20 == 0) {
            sons(level, r);
        }
        if (t % 10 == 0) {
            ecrans(level, t);
        }
    }

    // ------------------------------------------------------------------ lampes

    private static void lampes(ServerLevel level, RandomSource r) {
        for (int[] l : LaboBuilder.LAMPES) {
            BlockPos p = LaboBuilder.abs(l[0], l[1], l[2]);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.REDSTONE_LAMP)) {
                continue;
            }
            Integer reste = ETEINTES.get(p);
            if (reste != null) {
                if (reste <= 1) {
                    ETEINTES.remove(p);
                    level.setBlock(p, s.setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                } else {
                    ETEINTES.put(p, reste - 1);
                    // ça grésille : elle se rallume une fraction de seconde
                    if (r.nextInt(4) == 0) {
                        level.setBlock(p, s.setValue(RedstoneLampBlock.LIT, !s.getValue(RedstoneLampBlock.LIT)), Block.UPDATE_CLIENTS);
                    }
                }
            } else if (!s.getValue(RedstoneLampBlock.LIT)) {
                level.setBlock(p, s.setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
            } else if (r.nextInt(45) == 0) {
                ETEINTES.put(p, 2 + r.nextInt(14));
                level.setBlock(p, s.setValue(RedstoneLampBlock.LIT, false), Block.UPDATE_CLIENTS);
                level.playSound(null, p, SoundEvents.COPPER_BULB_TURN_OFF, SoundSource.BLOCKS, 0.5F, 0.6F + r.nextFloat() * 0.3F);
            }
        }
    }

    // ------------------------------------------------------------------ arcs électriques

    private static Vec3 sommet(int i) {
        int[] b = LaboBuilder.BOBINES[i];
        return Vec3.atCenterOf(LaboBuilder.abs(b[0], LaboBuilder.HAUT_BOBINE, b[1])).add(0, 0.3, 0);
    }

    /** Un arc électrique en zigzag depuis la bobine {@code i} jusqu'à {@code cible}. */
    static void arc(ServerLevel level, int i, Vec3 cible) {
        RandomSource r = level.random;
        Vec3 depart = sommet(i);
        if (depart.distanceToSqr(cible) < 1) {
            cible = depart.add(r.nextGaussian() * 3, -2 - r.nextDouble() * 3, r.nextGaussian() * 3);
        }
        int n = (int) Math.max(8, depart.distanceTo(cible) * 2.2);
        Vec3 precedent = depart;
        for (int k = 1; k <= n; k++) {
            double f = (double) k / n;
            double ecart = k == n ? 0 : 0.45 * Math.sin(Math.PI * f) + 0.15;
            Vec3 p = depart.lerp(cible, f).add(r.nextGaussian() * ecart, r.nextGaussian() * ecart, r.nextGaussian() * ecart);
            for (int m = 0; m < 3; m++) {
                Vec3 q = precedent.lerp(p, m / 3.0);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            }
            precedent = p;
        }
        level.sendParticles(ParticleTypes.END_ROD, cible.x, cible.y, cible.z, 4, 0.1, 0.1, 0.1, 0.05);
        level.playSound(null, depart.x, depart.y, depart.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.35F, 1.7F + r.nextFloat() * 0.3F);
    }

    // ------------------------------------------------------------------ bulles, gouttes, le Sujet Zéro

    private static void particules(ServerLevel level, RandomSource r, long t) {
        for (int[] b : LaboBuilder.BULLES) {
            BlockPos p = LaboBuilder.abs(b[0], 2, b[1]);
            if (level.isLoaded(p)) {
                level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, p.getX() + 0.5, p.getY() + 0.3, p.getZ() + 0.5, 2, 0.5, 0.1, 0.5, 0.05);
            }
        }
        if (r.nextInt(3) == 0) {
            int[] g = LaboBuilder.GOUTTES[r.nextInt(LaboBuilder.GOUTTES.length)];
            BlockPos p = LaboBuilder.abs(g[0], g[1], g[2]);
            level.sendParticles(ParticleTypes.DRIPPING_WATER, p.getX() + r.nextDouble(), p.getY() + 0.9, p.getZ() + r.nextDouble(), 1, 0, 0, 0, 0);
        }
        BlockPos zero = LaboBuilder.abs(LaboBuilder.ZERO.getX(), LaboBuilder.ZERO.getY(), LaboBuilder.ZERO.getZ());
        if (r.nextInt(4) == 0) {
            level.sendParticles(ParticleTypes.SCULK_SOUL, zero.getX() + 0.5 + r.nextGaussian() * 3, zero.getY() + 0.2, zero.getZ() + 0.5 + r.nextGaussian() * 3,
                    1, 0, 0.05, 0, 0.02);
        }
        // le Sujet 05 (l'enderman mutant) : des particules violettes dans sa cuve
        BlockPos cinq = LaboBuilder.abs(15, 5, -15);
        level.sendParticles(ParticleTypes.PORTAL, cinq.getX() + 0.5, cinq.getY(), cinq.getZ() + 0.5, 6, 1.2, 2.5, 1.2, 0.3);
        // le sceau de la salle des rituels exhale une brume rouge
        BlockPos sceau = LaboBuilder.abs(LaboBuilder.RX, 1, LaboBuilder.RZ);
        if (level.isLoaded(sceau)) {
            for (int k = 0; k < 2; k++) {
                double a = r.nextDouble() * Math.PI * 2, d = 3.5 + r.nextDouble() * (LaboBuilder.RAYON_SCEAU - 3.5);
                level.sendParticles(BRUME, sceau.getX() + 0.5 + Math.cos(a) * d, sceau.getY() + 0.15, sceau.getZ() + 0.5 + Math.sin(a) * d, 1, 0.1, 0.2, 0.1, 0.005);
            }
        }
        // de petites étincelles aux électrodes de la table
        if (!Experiences.enCours() && r.nextInt(3) == 0) {
            BlockPos e = LaboBuilder.abs(r.nextBoolean() ? -1 : 2, 3, 0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX() + 0.5, e.getY() + 0.5, e.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0.1);
        }
    }

    // ------------------------------------------------------------------ les bruits

    private static void sons(ServerLevel level, RandomSource r) {
        // quelque chose frappe derrière la porte d'isolement
        if (r.nextInt(12) == 0) {
            BlockPos p = LaboBuilder.abs(LaboBuilder.ISOLEMENT.getX(), LaboBuilder.ISOLEMENT.getY(), LaboBuilder.ISOLEMENT.getZ());
            int coups = 1 + r.nextInt(3);
            for (int k = 0; k < coups; k++) {
                level.playSound(null, p, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.HOSTILE, 0.9F, 0.6F + r.nextFloat() * 0.2F);
            }
        }
        for (ServerPlayer joueur : level.players()) {
            // seulement au labo : le WOOHP, plus loin dans la même dimension, n'a pas ces bruits-là
            if (r.nextInt(10) != 0 || Math.abs(joueur.getZ()) > LaboBuilder.BORD + 20) {
                continue;
            }
            double angle = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 10;
            double x = joueur.getX() + Math.cos(angle) * d, z = joueur.getZ() + Math.sin(angle) * d, y = joueur.getY() + 1;
            int choix = r.nextInt(12);
            SoundEvent son = switch (choix) {
                case 0 -> SoundEvents.AMBIENT_CAVE.value();
                case 1 -> SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
                case 2 -> SoundEvents.GHAST_SCREAM;
                case 3 -> SoundEvents.IRON_DOOR_CLOSE;
                case 4 -> SoundEvents.CHAIN_BREAK;
                case 5 -> SoundEvents.WARDEN_HEARTBEAT;
                case 6 -> SoundEvents.SCULK_CLICKING;
                case 7 -> SoundEvents.BELL_RESONATE;
                case 8 -> SoundEvents.ANVIL_LAND;
                case 9 -> SoundEvents.VILLAGER_HURT;
                case 10 -> SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value();
                default -> r.nextInt(4) == 0 ? SoundEvents.ENDERMAN_STARE : SoundEvents.AMBIENT_CAVE.value();
            };
            float volume = choix == 2 || choix == 8 ? 0.25F : 0.7F;
            float hauteur = choix == 0 || choix == 10 || choix == 5 ? 1F : 0.5F + r.nextFloat() * 0.3F;
            level.playSound(null, x, y, z, son, SoundSource.AMBIENT, volume, hauteur);
        }
    }

    // ------------------------------------------------------------------ les écrans

    private static void ecrans(ServerLevel level, long t) {
        if (t - rechercheEcrans > 100 || ecrans.stream().anyMatch(e -> e.isRemoved())) {
            rechercheEcrans = t;
            AABB zone = new AABB(LaboBuilder.abs(0, 0, 0)).inflate(LaboBuilder.AILE + 2, LaboBuilder.HAUT, LaboBuilder.AILE + 2);
            ecrans = level.getEntities(EntityType.TEXT_DISPLAY, zone, e -> e.getTags().contains(LaboBuilder.ECRAN));
        }
        int frame = (int) (t / 10);
        for (Display.TextDisplay e : ecrans) {
            Component texte;
            if (e.getTags().contains(LaboBuilder.ECRAN + ".table")) {
                texte = moniteur(frame, level.random);
            } else if (e.getTags().contains(LaboBuilder.ECRAN + ".alerte")) {
                texte = alerte(frame);
            } else {
                texte = cuves(frame, level.random);
            }
            ecrire(e, texte);
        }
    }

    private static final String ECG = "▁▁▁▁▁▂▃▂▁▁▁▇▁▄▁▁▁▂▂▃▂▁▁▁▁▁";

    private static Component moniteur(int frame, RandomSource r) {
        boolean actif = Experiences.enCours();
        int decalage = (frame * (actif ? 3 : 1)) % ECG.length();
        String ligne = (ECG.substring(decalage) + ECG + ECG).substring(0, 22);
        int bpm = actif ? 150 + r.nextInt(70) : 0;
        ChatFormatting c = actif ? ChatFormatting.RED : ChatFormatting.GREEN;
        MutableComponent m = Component.literal("MONITEUR — TABLE 1\n").withStyle(MONO.withColor(ChatFormatting.DARK_GREEN));
        m.append(Component.literal("SUJET : " + (actif ? Experiences.dernierSujet : "aucun") + "\n").withStyle(MONO.withColor(ChatFormatting.GREEN)));
        m.append(Component.literal((actif ? "♥ " + bpm + " bpm" : "♥ --- bpm") + "\n").withStyle(MONO.withColor(c)));
        m.append(Component.literal((actif ? ligne : "▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁") + "\n").withStyle(MONO.withColor(c)));
        if (!Experiences.dernierResultat.isEmpty()) {
            m.append(Component.literal("DERNIER : " + Experiences.dernierSujet + "\n→ " + Experiences.dernierResultat).withStyle(MONO.withColor(ChatFormatting.GRAY)));
        } else {
            m.append(Component.literal("en attente d'un sujet…").withStyle(MONO.withColor(ChatFormatting.GRAY)));
        }
        return m;
    }

    private static Component alerte(int frame) {
        boolean allume = frame % 2 == 0;
        MutableComponent m = Component.literal(allume ? "⚠ ALERTE ⚠\n" : "  ALERTE  \n")
                .withStyle(MONO.withColor(allume ? ChatFormatting.RED : ChatFormatting.DARK_RED).withBold(true));
        m.append(Component.literal("SUJET 12 HORS CONFINEMENT\n").withStyle(MONO.withColor(ChatFormatting.RED).withBold(false)));
        m.append(Component.literal("dernière position : CELLULES\n").withStyle(MONO.withColor(ChatFormatting.GRAY).withBold(false)));
        m.append(Component.literal("porte d'isolement : " + (frame % 7 == 0 ? "OUVERTE" : "fermée")).withStyle(MONO.withColor(ChatFormatting.GRAY).withBold(false)));
        return m;
    }

    private static Component cuves(int frame, RandomSource r) {
        MutableComponent m = Component.literal("NIVEAUX DES CUVES\n").withStyle(MONO.withColor(ChatFormatting.DARK_GREEN));
        String[] noms = {"01", "02", "03", "04", "06", "08", "09", "11", "12"};
        for (int k = 0; k < noms.length; k++) {
            int niveau = noms[k].equals("12") ? 0 : 70 + (int) (Math.sin(frame * 0.3 + k) * 12) + r.nextInt(5);
            int barres = niveau / 13;
            String jauge = "▮".repeat(barres) + "▯".repeat(Math.max(0, 7 - barres));
            m.append(Component.literal("CUVE " + noms[k] + " " + jauge + " " + (niveau == 0 ? "VIDE !" : niveau + "%") + "\n")
                    .withStyle(MONO.withColor(niveau == 0 ? ChatFormatting.RED : ChatFormatting.GREEN)));
        }
        m.append(Component.literal("SUJET ZÉRO : activité " + (frame % 3 == 0 ? "▲▲▲" : "▲▲")).withStyle(MONO.withColor(ChatFormatting.GOLD)));
        return m;
    }

    /** Change le texte d'un écran (le champ est privé : on passe par la réflexion, sinon par la sauvegarde NBT). */
    @SuppressWarnings("unchecked")
    private static void ecrire(Display.TextDisplay e, Component texte) {
        if (texteEcran == null && !reflexionRatee) {
            try {
                Field f = Display.TextDisplay.class.getDeclaredField("DATA_TEXT_ID");
                f.setAccessible(true);
                texteEcran = (EntityDataAccessor<Component>) f.get(null);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                reflexionRatee = true;
            }
        }
        if (texteEcran != null) {
            e.getEntityData().set(texteEcran, texte);
            return;
        }
        CompoundTag tag = new CompoundTag();
        e.saveWithoutId(tag);
        tag.putString("text", Component.Serializer.toJson(texte, e.registryAccess()));
        e.load(tag);
    }

    private LaboVie() {
    }
}
