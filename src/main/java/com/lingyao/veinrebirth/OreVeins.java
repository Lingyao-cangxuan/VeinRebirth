package com.lingyao.veinrebirth;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 原版「大型矿脉」处理。
 * <p>
 * Minecraft 1.18 起加入了一套和矿物簇完全无关的生成机制：{@code OreVeinifier}（大型矿脉 / ore vein）。
 * 它由地形噪声（{@code noise_settings} 里的 {@code vein_toggle} / {@code vein_ridged} / {@code vein_gap}
 * 三张噪声图）在 <b>地形生成阶段</b>直接写入方块，走的是 {@code NoiseChunk} 的 BlockStateFiller，
 * <b>不经过任何 placed_feature</b>。因此 {@code forge:remove_features} 生物群系修改器在原理上就拦不住它，
 * 关了原版矿物之后世界里仍会留下这些矿脉（约占原版矿物总量的百分之几）。
 * <p>
 * 原版只有两种矿脉，Y 区间 -60 ~ 51：
 * <ul>
 *   <li>铜矿脉：铜矿石 + 粗铜块（生铜块），填充岩石为花岗岩</li>
 *   <li>铁矿脉：铁矿石 + 粗铁块（生铁块），填充岩石为凝灰岩</li>
 * </ul>
 * 本类在矿物特征运行时（此时地形已经生成完毕）把矿脉专有的方块换成周围最常见的岩石，
 * 从而让"关闭矿物 = 世界上真的没有这种矿物"成立。替换与回填的具体实现在 {@link RockFiller}。
 * <p>
 * 注意：矿脉只存在于开启了 {@code ore_veins_enabled} 的噪声设置里，
 * 也就是主世界、大型生物群系、放大化三种世界类型；下界 / 末地 / 洞穴世界都没有。
 */
public final class OreVeins {

    /** 原版矿脉的生成高度区间（写死在 OreVeinifier 里）。 */
    public static final int MIN_Y = -60;
    public static final int MAX_Y = 51;

    /** 铜矿脉会出现的方块。 */
    private static final Set<Block> COPPER_VEIN = Set.of(
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.RAW_COPPER_BLOCK);

    /** 铁矿脉会出现的方块。 */
    private static final Set<Block> IRON_VEIN = Set.of(
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK);

    private OreVeins() {
    }

    /** 所有矿脉专有的方块（含粗矿块），用于 /veinrebirth refresh clean 的彻底清理。 */
    public static Set<Block> allVeinBlocks() {
        Set<Block> set = new HashSet<>(COPPER_VEIN);
        set.addAll(IRON_VEIN);
        return set;
    }

    /**
     * 当前配置下需要清除的矿脉方块。
     * <p>
     * 返回空集合表示无需清理（矿脉开着，且对应的铜 / 铁也开着）。
     */
    public static Set<Block> blocksToStrip() {
        Set<Block> set = new HashSet<>();
        if (!ConfigManager.isVeinOresEnabled()) {
            // 矿脉整体关闭：铜、铁的矿脉方块全部清掉
            set.addAll(COPPER_VEIN);
            set.addAll(IRON_VEIN);
            return set;
        }
        // 矿脉开着，但某一种矿物被单独关掉了，也只清那一种
        if (!ConfigManager.get(OreType.COPPER).isEnabled()) {
            set.addAll(COPPER_VEIN);
        }
        if (!ConfigManager.get(OreType.IRON).isEnabled()) {
            set.addAll(IRON_VEIN);
        }
        return set;
    }

    /**
     * 清除一个区块内的指定矿脉方块，替换为周围最常见的岩石。
     * 只在矿脉可能出现的高度区间（Y {@value #MIN_Y} ~ {@value #MAX_Y}）内扫描。
     *
     * @return 实际被替换掉的方块数量
     */
    public static int stripInChunk(WorldGenLevel level, int chunkX, int chunkZ, Set<Block> strip) {
        // 大型矿脉只存在于主世界类地形（噪声设置里开了 ore_veins_enabled 的那些）
        return RockFiller.strip(level, chunkX, chunkZ, strip, MIN_Y, MAX_Y, RockFiller.Terrain.OVERWORLD);
    }

    /** 与上面等价，但由调用方直接提供区块对象（例如区块刷新时已经有 LevelChunk 在手）。 */
    public static int stripInChunk(WorldGenLevel level, ChunkAccess chunk, int chunkX, int chunkZ, Set<Block> strip) {
        return RockFiller.strip(level, chunk, chunkX, chunkZ, strip, MIN_Y, MAX_Y, RockFiller.Terrain.OVERWORLD);
    }
}
