package com.lingyao.veinrebirth;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * 区块矿物刷新（retrogen）。
 * <p>
 * 已经生成过的区块不会自动重新生成地形，所以这里主动对指定范围内的区块再跑一遍矿物生成：
 * 只有原本是石头 / 深板岩 / 下界岩的位置才会被替换成矿物，玩家建筑、洞穴等不受影响。
 * <p>
 * {@link CleanMode#MANAGED} 会先把范围内本模组管理过的矿物方块替换回地形岩石（按邻域采样：
 * 末地补末地石、下界补下界岩、主世界补深板岩或石头）再重新生成，效果接近"按新配置重新生成矿物"，
 * 但会连同该范围内原有矿物一起抹掉。{@link CleanMode#ALL} 在此基础上还会清掉本模组从未接管的
 * 模组矿物方块（这些清掉后不会重建）。
 * <p>
 * 注意：该方法在服务端主线程同步执行，半径越大越慢（上限 {@value #MAX_RADIUS} 区块）。
 */
public final class ChunkRefresher {

    /** 刷新半径上限，防止一次刷太多区块把主线程卡死。 */
    public static final int MAX_RADIUS = 8;

    private static final long SEED_SALT = 341873128712L;

    private static final Map<OreGroup, ResourceKey<PlacedFeature>> PLACED_FEATURE_KEYS = new EnumMap<>(OreGroup.class);

    static {
        for (OreGroup group : OreGroup.values()) {
            PLACED_FEATURE_KEYS.put(group, ResourceKey.create(Registries.PLACED_FEATURE,
                    new ResourceLocation(VeinRebirthMod.MOD_ID, group.placedFeaturePath())));
        }
    }

    private ChunkRefresher() {
    }

    /** 清除力度。 */
    public enum CleanMode {
        /** 只重跑矿物生成，不预先清除范围内已有矿物。 */
        NONE,
        /** 清除本模组管理过的方块：原版 11 种 + 当前已接管 + 曾经接管过的模组矿物。 */
        MANAGED,
        /** 在 MANAGED 之上再清除所有已识别模组矿物的方块（含从未接管的；这些清掉后不会重建）。 */
        ALL;

        public boolean cleans() {
            return this != NONE;
        }

        public boolean evenUnmanaged() {
            return this == ALL;
        }
    }

    /** 刷新结果。后两个计数只在实际清除了方块时非零。 */
    public record Result(int chunksScanned, int chunksTouched, int oreBlocksRemoved, int veinBlocksRemoved,
            int radius) {
    }

    /**
     * 该清除力度实际要抹掉的方块集合（{@code /veinrebirth refresh clean} 与自检共用同一份逻辑，
     * 以免测试测的是另一套复刻算法）。
     * <p>
     * 分三层：
     * <ol>
     *   <li>{@link OreType#handledBlocks()} —— 原版 11 种 + 当前已接管的模组矿物；</li>
     *   <li>{@link OreType#everHandledBlocks()} —— 曾经接管过、但现在已关掉接管的模组矿物。
     *       那些方块是本模组先前生成的，不在这里补上就会永久留在世界里再也清不掉；</li>
     *   <li>{@link OreType#allModdedBlocks()} —— 只有 {@link CleanMode#ALL} 才加入：
     *       连本模组从未接管、由别的模组自己生成的矿石也一并清掉，清后不会重建。</li>
     * </ol>
     * 原版大型矿脉方块不属于任何模组矿物，始终一并纳入。
     */
    public static Set<Block> cleanTargets(CleanMode mode) {
        if (!mode.cleans()) {
            return Set.of();
        }
        Set<Block> set = new HashSet<>(OreType.handledBlocks());
        set.addAll(OreType.everHandledBlocks());
        if (mode.evenUnmanaged()) {
            set.addAll(OreType.allModdedBlocks());
        }
        set.addAll(OreVeins.allVeinBlocks());
        return set;
    }

    public static Result refresh(ServerLevel level, BlockPos center, int radius, CleanMode mode) {
        int effectiveRadius = Math.max(1, Math.min(MAX_RADIUS, radius));
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        // 多预加载一圈，避免从区块边缘溢出的矿脉写进未加载的区块
        for (int dx = -effectiveRadius - 1; dx <= effectiveRadius + 1; dx++) {
            for (int dz = -effectiveRadius - 1; dz <= effectiveRadius + 1; dz++) {
                level.getChunk(centerChunkX + dx, centerChunkZ + dz);
            }
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Set<Block> managedBlocks = cleanTargets(mode);
        // 大型矿脉不走数据包，普通 refresh 也要清理一遍，否则关掉矿脉后旧区块里会一直留着
        Set<Block> veinStrip = OreVeins.blocksToStrip();
        RockFiller.Terrain terrain = terrainOf(level);

        int chunksScanned = 0;
        int chunksTouched = 0;
        int oreBlocksRemoved = 0;
        int veinBlocksRemoved = 0;

        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
            for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
                LevelChunk chunk = level.getChunk(centerChunkX + dx, centerChunkZ + dz);
                if (chunk == null) {
                    continue;
                }
                chunksScanned++;

                ChunkPos chunkPos = chunk.getPos();
                BlockPos probe = new BlockPos(chunkPos.getMinBlockX() + 8, level.getMinBuildHeight(),
                        chunkPos.getMinBlockZ() + 8);

                if (mode.cleans()) {
                    // 复用与"接管清理"完全相同的回填逻辑：按邻域采样地形岩石，
                    // 末地补末地石、下界补下界岩，不会再统一填成石头
                    oreBlocksRemoved += RockFiller.strip(level, chunk, chunkPos.x, chunkPos.z, managedBlocks,
                            level.getMinBuildHeight(), level.getMaxBuildHeight() - 1, terrain);
                }
                if (!veinStrip.isEmpty()) {
                    veinBlocksRemoved += OreVeins.stripInChunk(level, chunk, chunkPos.x, chunkPos.z, veinStrip);
                }

                // 同一个区块用固定种子，重复执行结果一致
                RandomSource random = RandomSource.create(level.getSeed() ^ (chunkPos.toLong() * SEED_SALT));

                boolean touched = false;
                for (OreGroup group : OreGroup.values()) {
                    if (!biomeUsesGroup(level, probe, group)) {
                        continue;
                    }
                    ConfigOreFeature feature = VeinRebirthMod.ORE_FEATURES.get(group).get();
                    if (feature == null) {
                        continue;
                    }
                    FeaturePlaceContext<NoneFeatureConfiguration> context = new FeaturePlaceContext<>(
                            Optional.empty(), level, generator, random, probe, NoneFeatureConfiguration.INSTANCE);
                    if (feature.place(context)) {
                        touched = true;
                    }
                }

                chunk.setUnsaved(true);
                if (touched) {
                    chunksTouched++;
                }
            }
        }

        return new Result(chunksScanned, chunksTouched, oreBlocksRemoved, veinBlocksRemoved, effectiveRadius);
    }

    /** 该区块所在生物群系是否被配置了对应分组的矿物（与数据包里的 add_features 保持一致）。 */
    private static boolean biomeUsesGroup(ServerLevel level, BlockPos pos, OreGroup group) {
        ResourceKey<PlacedFeature> key = PLACED_FEATURE_KEYS.get(group);
        try {
            Holder<Biome> biome = level.getBiome(pos);
            for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    if (holder.is(key)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            VeinRebirthMod.LOGGER.debug("[VeinRebirth] Failed to check biome ore group: {}", e.getMessage());
        }
        return false;
    }

    /** 当前刷新所在维度对应的地形类型（决定回填兜底岩石：末地石 / 下界岩 / 深板岩-石头）。 */
    private static RockFiller.Terrain terrainOf(ServerLevel level) {
        if (level.dimension() == Level.END) {
            return RockFiller.Terrain.END;
        }
        if (level.dimension() == Level.NETHER) {
            return RockFiller.Terrain.NETHER;
        }
        if (level.dimension() == Level.OVERWORLD) {
            return RockFiller.Terrain.OVERWORLD;
        }
        return RockFiller.Terrain.OTHER;
    }
}
