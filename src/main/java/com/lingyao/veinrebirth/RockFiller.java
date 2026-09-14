package com.lingyao.veinrebirth;

import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 按高度区间清除区块内的指定方块，并回填成周围最常见的岩石。
 * <p>
 * 两个场景共用这一套逻辑：
 * <ul>
 *   <li>原版「大型矿脉」的清理（{@link OreVeins}）——它走地形噪声，数据包拦不住；</li>
 *   <li>模组矿物的「接管」清理（{@link ConfigOreFeature}）——先抹掉其它模组生成的这种矿物，
 *       再按本模组的数值重新生成，避免两份叠加。</li>
 * </ul>
 * <b>回填策略</b>：先看六邻域里出现次数最多的方块，样本不足时放宽到 26 邻域；采样时排除空气、
 * 液体、非完整方块、本次要清除的方块本身，<b>并且只认天然岩层材料</b>（{@link Terrain#isNaturalRock}）。
 * 这样花岗岩里的铜矿脉补回花岗岩、末地石里的矿补回末地石、下界岩里的矿补回下界岩，
 * 而贴着黑曜石柱 / 末地石砖 / 萤石 / 苔石 / 玩家建筑的那些矿物被清除后，补回的是该维度的基础岩石，
 * 不会把结构方块"复制"到旁边的格子里。只有在完全采不到样本时（例如整片矿脉悬空在洞穴里）
 * 才退回 {@link Terrain} 给出的兜底岩石。
 * <p>
 * <b>两遍处理</b>：先把所有待清除的坐标收集完，再统一回填。如果边扫边填，先回填的那块岩石会被
 * 后面位置的采样当成"邻居"投票，一个孤立的异色方块就能顺着检测顺序扩散成一大片——早期版本
 * 在末地出现过整片石头、在主世界出现过整片下界岩，都是这个原因。
 */
public final class RockFiller {

    /**
     * 地形类型：决定"什么算天然岩层"以及"采不到样本时用什么岩石兜底"。
     * <p>
     * 直接由 {@link OreGroup} 推导，不需要去问世界对象要维度——特征本身就是按维度挂载的。
     */
    public enum Terrain {
        OVERWORLD, NETHER, END, OTHER;

        /** 矿物分组 → 地形。{@link OreGroup#EMERALD} 属于主世界的特殊子集。 */
        public static Terrain of(OreGroup group) {
            if (group == null) {
                return OTHER;
            }
            return switch (group) {
                case NETHER -> NETHER;
                case END -> END;
                case OVERWORLD, EMERALD -> OVERWORLD;
            };
        }

        /**
         * 这个方块有没有资格当回填材料——也就是"它算不算天然岩层的一部分"。
         * <p>
         * <b>为什么必须是白名单，而不是黑名单</b>：早期版本只要求邻居是"完整实心方块"，
         * 于是黑曜石柱旁边的矿石被清除后回填成黑曜石（柱子凭空变粗）、末地城附近的回填成末地石砖、
         * 下界要塞附近的回填成下界砖、你家仓库旁边的回填成钻石块。黑名单永远会漏，白名单不会。
         * <p>
         * <b>为什么还要查标签</b>：模组的岩石种类没法穷举，所以先认 Forge 的通用标签，
         * 模组只要规规矩矩打了 {@code forge:stone} 之类的标签就能被认出来；原版方块再用显式集合兜住。
         */
        public boolean isNaturalRock(BlockState state) {
            Block block = state.getBlock();
            // 先走原版集合：哈希查找，最便宜
            boolean known = switch (this) {
                case END -> block == Blocks.END_STONE;
                case NETHER -> NETHER_ROCKS.contains(block);
                case OVERWORLD, OTHER -> OVERWORLD_ROCKS.contains(block);
            };
            if (known) {
                return true;
            }
            // 模组岩石通常只会出现在标签里。标签按维度限定，避免跨维度误判；
            // 圆石类标签（forge:cobblestone）刻意不用——它里面带着苔石、虫蚀石，
            // 那些是地牢 / 要塞的材料，不是地形。
            return switch (this) {
                case END -> state.is(FORGE_END_STONES);
                case NETHER -> state.is(FORGE_NETHERRACK);
                case OVERWORLD, OTHER -> state.is(FORGE_STONE);
            };
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 通用标签：模组岩石只要打了标签就能被认作岩层，不必逐个枚举。
     * <p>
     * 刻意<b>不</b>包含 {@code forge:cobblestone}——那个标签里带着苔石、虫蚀石，
     * 属于地牢 / 要塞的材料。
     */
    private static final TagKey<Block> FORGE_STONE = forgeTag("stone");
    private static final TagKey<Block> FORGE_END_STONES = forgeTag("end_stones");
    private static final TagKey<Block> FORGE_NETHERRACK = forgeTag("netherrack");

    /**
     * 主世界的天然岩层。这里刻意<b>不含</b>圆石、石砖、深板岩砖、苔石——
     * 那些是地牢 / 要塞 / 玩家建筑的材料，不是地形。
     */
    private static final Set<Block> OVERWORLD_ROCKS = Set.of(
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
            Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MOSS_BLOCK, Blocks.MUD,
            Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.CLAY, Blocks.TERRACOTTA);

    /**
     * 下界的天然岩层。<b>不含</b>萤石、下界砖、黑曜石——萤石是发光结构，后两者是堡垒 / 结构材料。
     */
    private static final Set<Block> NETHER_ROCKS = Set.of(
            Blocks.NETHERRACK, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.GRAVEL, Blocks.MAGMA_BLOCK,
            Blocks.WARPED_NYLIUM, Blocks.CRIMSON_NYLIUM);

    private RockFiller() {
    }

    private static TagKey<Block> forgeTag(String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation("forge", path));
    }

    /** 在整个建筑高度范围内清除指定方块。 */
    public static int strip(WorldGenLevel level, int chunkX, int chunkZ, Set<Block> strip, Terrain terrain) {
        return strip(level, chunkX, chunkZ, strip,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1, terrain);
    }

    /** 在 [fromY, toY] 范围内清除指定方块（世界生成期间使用）。 */
    public static int strip(WorldGenLevel level, int chunkX, int chunkZ, Set<Block> strip,
            int fromY, int toY, Terrain terrain) {
        if (strip == null || strip.isEmpty()) {
            return 0;
        }
        ChunkAccess chunk;
        try {
            // ChunkStatus.EMPTY + load=false：世界生成期间当前区块是 ProtoChunk，
            // 这条路径直接读区域缓存、不做状态校验也不会触发加载，是最安全的取法。
            chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
        } catch (Exception e) {
            LOGGER.debug("[VeinRebirth] Failed to access chunk ({}, {}): {}", chunkX, chunkZ, e.toString());
            return 0;
        }
        return strip(level, chunk, chunkX, chunkZ, strip, fromY, toY, terrain);
    }

    /** 与上面等价，但由调用方直接提供区块对象（例如区块刷新时已经有 LevelChunk 在手）。 */
    public static int strip(WorldGenLevel level, ChunkAccess chunk, int chunkX, int chunkZ,
            Set<Block> strip, int fromY, int toY, Terrain terrain) {
        if (strip == null || strip.isEmpty() || chunk == null) {
            return 0;
        }
        int minY = Math.max(chunk.getMinBuildHeight(), fromY);
        int maxY = Math.min(chunk.getMaxBuildHeight() - 1, toY);
        if (maxY < minY) {
            return 0;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null) {
            return 0;
        }
        int chunkMinY = chunk.getMinBuildHeight();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        int firstIndex = Math.max(0, (minY - chunkMinY) >> 4);
        int lastIndex = Math.min(sections.length - 1, (maxY - chunkMinY) >> 4);

        // ---------------- 第一遍：只收集坐标，先不动任何方块 ----------------
        LongArrayList targets = new LongArrayList();
        for (int index = firstIndex; index <= lastIndex; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            // 调色板预判：这一层里根本没有目标方块时直接跳过，避免无谓的全段遍历
            if (!section.getStates().maybeHas(state -> strip.contains(state.getBlock()))) {
                continue;
            }
            int sectionBaseY = chunkMinY + (index << 4);
            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;
                if (worldY < minY || worldY > maxY) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!strip.contains(section.getBlockState(x, y, z).getBlock())) {
                            continue;
                        }
                        targets.add(BlockPos.asLong(baseX + x, worldY, baseZ + z));
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }

        // ---------------- 第二遍：统一回填 ----------------
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < targets.size(); i++) {
            long packed = targets.getLong(i);
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            level.setBlock(cursor, pickRock(level, cursor, strip, terrain), 2);
        }
        return targets.size();
    }

    /** 给被清掉的方块挑一块合适的岩石：先六邻域，样本不足再放宽到 26 邻域。 */
    private static BlockState pickRock(WorldGenLevel level, BlockPos pos, Set<Block> strip, Terrain terrain) {
        Block best = vote(level, pos, strip, 1, terrain);
        if (best == null) {
            best = vote(level, pos, strip, 2, terrain);
        }
        return best != null ? best.defaultBlockState() : fallback(terrain, pos.getY());
    }

    /**
     * 统计半径 r 的立方邻域内出现次数最多的<b>天然岩层材料</b>。
     * <p>
     * 结构方块（黑曜石、砖、萤石、储物方块……）在这里就被挡掉了，所以返回的一定是能安全回填的方块。
     * 采不到任何可用样本时返回 null，交给调用方放宽半径或走兜底。
     */
    private static Block vote(WorldGenLevel level, BlockPos pos, Set<Block> strip, int radius, Terrain terrain) {
        // 候选种类很少，用定长数组做线性计数，避免每个方块都建一个 HashMap
        Block[] found = new Block[64];
        int[] counts = new int[64];
        int size = 0;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState state;
                    try {
                        state = level.getBlockState(cursor);
                    } catch (Exception e) {
                        // 邻居落在尚未生成的区块里时读取会失败，跳过即可
                        continue;
                    }
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (strip.contains(block)) {
                        // 和自己在同一片矿脉里，不能当岩石用
                        continue;
                    }
                    if (!terrain.isNaturalRock(state)) {
                        // 结构方块不能当回填材料，否则会把黑曜石柱 / 砖墙"复制"到旁边的格子里
                        continue;
                    }
                    // 只收完整实心方块，避免把草、栅栏、告示牌之类当成回填材料
                    if (!state.isCollisionShapeFullBlock(level, cursor)) {
                        continue;
                    }
                    int index = indexOf(found, size, block);
                    if (index < 0) {
                        if (size < found.length) {
                            found[size] = block;
                            counts[size] = 1;
                            size++;
                        }
                    } else {
                        counts[index]++;
                    }
                }
            }
        }

        int bestIndex = -1;
        int bestCount = 0;
        for (int i = 0; i < size; i++) {
            if (counts[i] > bestCount) {
                bestCount = counts[i];
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? null : found[bestIndex];
    }

    private static int indexOf(Block[] found, int size, Block block) {
        for (int i = 0; i < size; i++) {
            if (found[i] == block) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 完全采不到样本时的兜底岩石。
     * <p>
     * 末地必须是末地石、下界必须是下界岩——早期版本统一用"Y<0 深板岩、否则石头"，
     * 结果在末地回填出大片石头。
     */
    private static BlockState fallback(Terrain terrain, int y) {
        return switch (terrain) {
            case END -> Blocks.END_STONE.defaultBlockState();
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case OVERWORLD, OTHER -> y < 0
                    ? Blocks.DEEPSLATE.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
        };
    }
}
