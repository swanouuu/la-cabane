package fr.cabane.histoire;

import com.mojang.serialization.MapCodec;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.hub.HubBuilder;
import fr.cabane.hub.HubData;
import fr.cabane.hub.HubEvents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * L'histoire de Beauvais : la bibliothèque secrète du bureau du Président, le laboratoire en dessous, la Salle
 * Zéro et son pupitre, la clé que donnent les Sept Reliques, le combat contre le Président Augmenté, la libération.
 * Et les Pillards du Sud, que l'hôte peut lâcher sur le village (/cabane raid).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Histoire {
    public static final String TAG_BOSS = "cabane_boss";
    public static final String TAG_SBIRE = "cabane_sbire";

    // ================================================================ la bibliothèque qui sonne creux

    /** Une bibliothèque comme les autres… sauf qu'un clic l'ouvre quelques secondes (avec celle du dessus ou du dessous). */
    public static class BibliothequeSecrete extends Block {
        public static final BooleanProperty OUVERTE = BooleanProperty.create("ouverte");
        public static final MapCodec<BibliothequeSecrete> CODEC = simpleCodec(BibliothequeSecrete::new);

        public BibliothequeSecrete(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(OUVERTE, false));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(OUVERTE);
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return state.getValue(OUVERTE) ? RenderShape.INVISIBLE : RenderShape.MODEL;
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(OUVERTE) ? Shapes.empty() : Shapes.block();
        }

        @Override
        protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
            return state.getValue(OUVERTE);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide && !state.getValue(OUVERTE)) {
                for (BlockPos p : List.of(pos, pos.above(), pos.below())) {
                    if (level.getBlockState(p).is(this)) {
                        level.setBlock(p, level.getBlockState(p).setValue(OUVERTE, true), 3);
                        level.scheduleTick(p, this, 100);
                    }
                }
                level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.6F, 0.6F);
                joueur.displayClientMessage(Component.literal("La bibliothèque pivote sans un bruit… un passage !").withStyle(ChatFormatting.DARK_GRAY), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            if (!state.getValue(OUVERTE)) {
                return;
            }
            // on ne referme pas sur quelqu'un
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) {
                level.scheduleTick(pos, this, 20);
                return;
            }
            level.setBlock(pos, state.setValue(OUVERTE, false), 3);
            level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, 0.6F);
        }
    }

    // ================================================================ le pupitre de la Salle Zéro

    public static class PupitreBlock extends HorizontalDirectionalBlock {
        public static final MapCodec<PupitreBlock> CODEC = simpleCodec(PupitreBlock::new);

        public PupitreBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player joueur, InteractionHand main, BlockHitResult hit) {
            if (!stack.is(CabaneItems.CLE_SALLE_ZERO.get())) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (joueur instanceof ServerPlayer j) {
                if (combat(j, pos)) {
                    stack.consume(1, joueur);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide) {
                joueur.displayClientMessage(Component.literal("Une serrure en forme de sept reliques… Il faut la Clé de la Salle Zéro.")
                        .withStyle(ChatFormatting.DARK_RED), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    // ================================================================ la clé, le dossier, les carnets

    /** Un objet de l'histoire, avec quelques lignes de description. */
    public static class ObjetLore extends Item {
        private final List<String> lignes;

        public ObjetLore(Properties properties, String... lignes) {
            super(properties);
            this.lignes = List.of(lignes);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            for (String l : lignes) {
                tooltip.add(Component.literal(l).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }
    }

    private static ItemStack livre(String titre, String auteur, String... pages) {
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> contenu = new ArrayList<>();
        for (String p : pages) {
            contenu.add(Filterable.passThrough(Component.literal(p)));
        }
        livre.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(titre), auteur, 0, contenu, true));
        return livre;
    }

    public static ItemStack journalDuPresident() {
        return livre("Carnet du Président", "Le Président",
                "Jour 1.\n\nLe village me fait confiance. Ils m'ont donné les clés de la Mairie. Le Conseil des Anciens est « suspendu » : en même temps, il ne servait à rien.",
                "Jour 30.\n\nLes Sept Reliques me gênent. Tant qu'elles sont sur le comptoir, les villageois se croient protégés et ne m'écoutent pas.\n\nJe les fais emporter cette nuit, aux quatre coins du monde, dans les pires donjons.",
                "Jour 52.\n\nPremier sujet. La potion verte rend fort, et obéissant. Effet secondaire : il ne parle plus.\n\nJ'annonce qu'il est « parti travailler à la capitale ». Personne ne pose de question.",
                "Jour 77.\n\nLes Pillards du Sud me paient en or pour savoir quand les gardes dorment. Chaque pillage fait peur au village. La peur me rend indispensable.\n\nC'est notre projet.",
                "Jour 101.\n\nLe projet AUGMENTÉ avance. Bientôt, je n'aurai plus besoin de gardes : je serai la garde.\n\nLa Salle Zéro est prête, au cœur du PLATO 6Z, chez nos amis les Pillards. Personne n'y entre sans les Sept Reliques. Et personne ne les retrouvera.",
                "Jour 102.\n\nUn avion en feu dans le ciel, au-dessus de la jungle. Des étrangers.\n\nTant mieux : de nouveaux sujets.");
    }

    public static ItemStack rapportSujet07() {
        return livre("Rapport : Sujet 07", "Laboratoire",
                "SUJET 07 — PIERRE\n\nMotif : posait trop de questions sur la « capitale ».\n\nRésiste à la potion verte. Continue de parler. Continue de poser des questions.",
                "Recommandation du Président :\n\n« Le garder en cellule. S'il continue, essayer la potion rouge. »\n\nNote : la potion rouge n'existe pas encore.");
    }

    // ================================================================ le combat final

    private static final String[] TIRADE = {
            "Vous avez trouvé les Sept Reliques… Bravo. C'était, en même temps, très bien et très embêtant.",
            "Vous croyez libérer Beauvais ? Beauvais, c'est moi.",
            "Le projet AUGMENTÉ est terminé. Laissez-moi vous montrer… mon programme.",
    };

    /** Ouvre la Salle Zéro : le Président apparaît, fusionné avec sa machine. Vrai si le combat commence. */
    public static boolean combat(ServerPlayer joueur, BlockPos pupitre) {
        ServerLevel level = joueur.serverLevel();
        if (bossVivant(level, pupitre)) {
            joueur.displayClientMessage(Component.literal("Le Président est déjà là !").withStyle(ChatFormatting.DARK_RED), true);
            return false;
        }
        BlockPos centre = pupitre.above(7);
        for (ServerPlayer p : level.getPlayers(p -> p.distanceToSqr(pupitre.getX(), pupitre.getY(), pupitre.getZ()) < 96 * 96)) {
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LE PRÉSIDENT AUGMENTÉ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Libérez Beauvais").withStyle(ChatFormatting.GOLD)));
            for (String ligne : TIRADE) {
                p.sendSystemMessage(Component.literal("Le Président : « " + ligne + " »").withStyle(ChatFormatting.GOLD));
            }
        }
        level.playSound(null, centre, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 3F, 0.8F);
        for (int k = 0; k < 4; k++) {
            LightningBolt eclair = EntityType.LIGHTNING_BOLT.create(level);
            if (eclair != null) {
                eclair.moveTo(centre.getX() + (k % 2 == 0 ? -6 : 6), pupitre.getY(), centre.getZ() + (k < 2 ? -6 : 6));
                eclair.setVisualOnly(true);
                level.addFreshEntity(eclair);
            }
        }
        // le Président Augmenté : le Harbinger de Cataclysm (réveillé), trois fois plus solide ; sinon, un Wither
        boolean cataclysm = BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse("cataclysm:the_harbinger"));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", cataclysm ? "cataclysm:the_harbinger" : "minecraft:wither");
        tag.putBoolean("Is_Act", true);
        tag.putBoolean("PersistenceRequired", true);
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, 90F, 0F);
            return ent;
        });
        if (!(e instanceof LivingEntity boss)) {
            return false;
        }
        boss.setCustomName(Component.literal("Le Président Augmenté").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        boss.addTag(TAG_BOSS);
        AttributeInstance vie = boss.getAttribute(Attributes.MAX_HEALTH);
        if (vie != null) {
            vie.setBaseValue(vie.getBaseValue() * 3);
        }
        AttributeInstance armure = boss.getAttribute(Attributes.ARMOR);
        if (armure != null) {
            armure.setBaseValue(armure.getBaseValue() + 8);
        }
        boss.setHealth(boss.getMaxHealth());
        level.addFreshEntity(boss);
        vague(level, centre, 4);
        return true;
    }

    private static boolean bossVivant(ServerLevel level, BlockPos pupitre) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pupitre).inflate(40), e -> e.getTags().contains(TAG_BOSS) && e.isAlive()).isEmpty();
    }

    /** Des gardes augmentés surgissent autour du pupitre. */
    private static void vague(ServerLevel level, BlockPos centre, int nombre) {
        for (int i = 0; i < nombre; i++) {
            Mob garde = EntityType.VINDICATOR.create(level);
            if (garde == null) {
                continue;
            }
            double a = i * Math.PI * 2 / nombre;
            garde.moveTo(centre.getX() + 0.5 + Math.cos(a) * 7, centre.getY() - 8, centre.getZ() + 0.5 + Math.sin(a) * 7, 0F, 0F);
            garde.finalizeSpawn(level, level.getCurrentDifficultyAt(centre), MobSpawnType.EVENT, null);
            garde.setCustomName(Component.literal("Garde augmenté").withStyle(ChatFormatting.DARK_RED));
            garde.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            garde.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            garde.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 600, 0));
            garde.addTag(TAG_SBIRE);
            garde.setPersistenceRequired();
            level.addFreshEntity(garde);
        }
    }

    /** Deux nouvelles vagues de gardes quand le Président faiblit (aux deux tiers, puis au tiers de sa vie). */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        ServerLevel level = server.overworld();
        BlockPos pupitre = Forteresse.pupitre(server);
        if (pupitre == null || !level.isLoaded(pupitre)) {
            return;
        }
        for (LivingEntity boss : level.getEntitiesOfClass(LivingEntity.class, new AABB(pupitre).inflate(40), e -> e.getTags().contains(TAG_BOSS))) {
            float part = boss.getHealth() / boss.getMaxHealth();
            for (String palier : new String[] {"palier_2", "palier_3"}) {
                float seuil = palier.equals("palier_2") ? 0.66F : 0.33F;
                if (part < seuil && !boss.getTags().contains(palier)) {
                    boss.addTag(palier);
                    vague(level, pupitre.above(7), palier.equals("palier_2") ? 5 : 7);
                    for (ServerPlayer p : level.getPlayers(p -> p.distanceToSqr(boss) < 96 * 96)) {
                        p.sendSystemMessage(Component.literal(palier.equals("palier_2") ? "Le Président : « Gardes ! À moi ! »"
                                : "Le Président : « Je ne suis pas encore à la fin de mon mandat ! »").withStyle(ChatFormatting.GOLD));
                    }
                }
            }
        }
    }

    /** Le Président Augmenté est vaincu : Beauvais est libre. */
    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        LivingEntity mort = event.getEntity();
        if (!mort.getTags().contains(TAG_BOSS) || !(mort.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        HubData.get(server).setLibere(true);
        var succes = server.getAdvancements().get(Cabane.id("histoire/liberation"));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BEAUVAIS EST LIBRE !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Le Président Augmenté est vaincu").withStyle(ChatFormatting.WHITE)));
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1F, 1F);
            if (succes != null && p.distanceToSqr(mort) < 128 * 128) {
                p.getAdvancements().award(succes, "victoire");
            }
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("★ Beauvais est libéré ! Le Président est en prison, sous la Mairie. Le village peut enfin respirer.")
                .withStyle(ChatFormatting.GOLD), false);
        for (LivingEntity sbire : level.getEntitiesOfClass(LivingEntity.class, mort.getBoundingBox().inflate(48), e -> e.getTags().contains(TAG_SBIRE))) {
            sbire.discard();
        }
        // le coffre du Président apparaît au pied de son pupitre
        BlockPos pupitre = Forteresse.pupitre(server);
        if (pupitre != null) {
            BlockPos c = pupitre.offset(0, -1, 3);
            level.setBlock(c, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            Forteresse.butin(level, c, "plato_president");
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, 80, 0.6, 0.8, 0.6, 0.4);
            server.getPlayerList().broadcastSystemMessage(Component.literal("★ Le coffre du Président est apparu au pied du pupitre.").withStyle(ChatFormatting.GOLD), false);
        }
        // le Président et sa garde quittent la place ; il finit dans une cellule du laboratoire
        HubEvents.apresLiberation(level);
    }

    // ================================================================ la découverte du laboratoire

    @SubscribeEvent
    public static void onJoueur(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur) || joueur.tickCount % 20 != 0) {
            return;
        }
        BlockPos centre = HubData.get(joueur.server).centre();
        if (centre == null || joueur.level() != joueur.server.overworld()) {
            return;
        }
        int[] z = HubBuilder.ZONE_SECRETE;
        double x = joueur.getX() - centre.getX(), y = joueur.getY() - centre.getY(), zz = joueur.getZ() - centre.getZ();
        if (x >= z[0] && x <= z[3] && y >= z[1] && y <= z[4] && zz >= z[2] && zz <= z[5]) {
            var succes = joueur.server.getAdvancements().get(Cabane.id("histoire/laboratoire"));
            if (succes != null && !joueur.getAdvancements().getOrStartProgress(succes).isDone()) {
                joueur.getAdvancements().award(succes, "entree");
                joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Le laboratoire").withStyle(ChatFormatting.DARK_RED)));
                joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Ce que le Président cache sous la Mairie").withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    // ================================================================ les Pillards du Sud (événement lancé par l'hôte)

    /** Une bande de pillards arrive du sud et marche sur le village. */
    public static int raid(ServerLevel level, int nombre) {
        BlockPos centre = HubData.get(level.getServer()).centre();
        if (centre == null) {
            return 0;
        }
        int x = centre.getX() + level.random.nextInt(31) - 15;
        int z = centre.getZ() + 110;
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int n = 0;
        for (int i = 0; i < nombre; i++) {
            EntityType<? extends PatrollingMonster> type = i % 3 == 2 ? EntityType.VINDICATOR : EntityType.PILLAGER;
            if (nombre >= 12 && i == nombre - 1) {
                type = EntityType.EVOKER;
            }
            PatrollingMonster pillard = type.create(level);
            if (pillard == null) {
                continue;
            }
            pillard.moveTo(x + level.random.nextInt(9) - 4, y, z + level.random.nextInt(9) - 4, 180F, 0F);
            pillard.finalizeSpawn(level, level.getCurrentDifficultyAt(centre), MobSpawnType.EVENT, null);
            pillard.setPatrolLeader(i == 0);
            pillard.setPatrolTarget(centre);
            pillard.setCustomName(Component.literal(i == 0 ? "Chef des Pillards du Sud" : "Pillard du Sud").withStyle(ChatFormatting.DARK_RED));
            pillard.setPersistenceRequired();
            level.addFreshEntity(pillard);
            n++;
        }
        if (nombre >= 8) {
            Mob ravageur = EntityType.RAVAGER.create(level);
            if (ravageur != null) {
                ravageur.moveTo(x, y, z, 180F, 0F);
                ravageur.finalizeSpawn(level, level.getCurrentDifficultyAt(centre), MobSpawnType.EVENT, null);
                ravageur.setCustomName(Component.literal("Ravageur des marais").withStyle(ChatFormatting.DARK_RED));
                level.addFreshEntity(ravageur);
                n++;
            }
        }
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("⚔ LES PILLARDS DU SUD ⚔").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("ils marchent sur Beauvais !").withStyle(ChatFormatting.GOLD)));
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2F, 1F);
        }
        return n;
    }

    private Histoire() {
    }
}
