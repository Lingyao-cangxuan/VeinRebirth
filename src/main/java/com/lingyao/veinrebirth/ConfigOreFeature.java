package com.lingyao.veinrebirth;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * 动态矿物生成特征。
 * <p>
 * 每个区块、每个分组只会被调用一次（放置特征里只挂了 in_square 定位修饰符），
 * 本类再按当前配置为分组内的每种矿物各自尝试 count 次矿脉生成。
 * 所有数值都是运行时读取的，因此调整配置后新生成的区块立即使用新数值。
 * <p>
 * 单条矿脉直接复用原版 {@link Feature#ORE} 的实现（椭球状矿脉 + 替换规则），矿物形态与原版一致。
 * <p>
 * <b>执行时机</b>：本特征挂在 {@code underground_decoration} 步，比各模组的矿物簇
 * （{@code underground_ores}）晚一步，因此可以在放置之前先把其它模组生成的同类矿物清掉，
 * 「接管」才有确定性——否则同一个步骤里的执行顺序无法保证，会出现两份叠加。
 * <p>
 * <b>放置前先清理</b>的内容有两类：
 * <ul>
 *   <li>原版大型矿脉（{@link OreVeins}）——它走地形噪声，数据包拦不住；</li>
 *   <li>被接管的模组矿物（配置里 {@code enabled = true} 的那些）——先抹掉其它模组的生成，
 *       再按本模组数值重新生成，从而让该矿物的总产量真正由滑块决定。</li>
 * </ul>
 */
public class ConfigOreFeature extends Feature<NoneFeatureConfiguration> {

    private final OreGroup group;

    public ConfigOreFeature(OreGroup group) {
        super(NoneFeatureConfiguration.CODEC);
        this.group = group;
    }

    public OreGroup group() {
        return this.group;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        ChunkGenerator generator = context.chunkGenerator();
        BlockPos origin = context.origin();

        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        int levelMinY = level.getMinBuildHeight();
        int levelMaxY = level.getMaxBuildHeight() - 1;

        boolean placedAny = false;

        // 必须先清理、后放置，否则会把刚生成的矿物也一起清掉
        Set<Block> strip = stripSet();
        if (!strip.isEmpty()) {
            RockFiller.strip(level, chunkX, chunkZ, strip, levelMinY, levelMaxY,
                    RockFiller.Terrain.of(this.group));
        }

        for (OreType type : OreType.values()) {
            if (type.group() != this.group) {
                continue;
            }
            OreSettings settings = ConfigManager.get(type);
            if (!settings.willGenerate()) {
                continue;
            }

            int minY = Mth.clamp(settings.getMinY(), levelMinY, levelMaxY);
            int maxY = Mth.clamp(settings.getMaxY(), levelMinY, levelMaxY);
            if (maxY < minY) {
                int swap = minY;
                minY = maxY;
                maxY = swap;
            }
            int heightSpan = maxY - minY + 1;

            OreConfiguration oreConfig = new OreConfiguration(type.targets(), settings.getSize());
            int count = settings.getCount();
            int weight = settings.getWeight();

            for (int i = 0; i < count; i++) {
                // 权重：按百分比决定这条矿脉是否真的生成
                if (weight < 100 && random.nextInt(100) >= weight) {
                    continue;
                }
                BlockPos pos = new BlockPos(
                        (chunkX << 4) + random.nextInt(16),
                        minY + random.nextInt(heightSpan),
                        (chunkZ << 4) + random.nextInt(16));
                if (Feature.ORE.place(new FeaturePlaceContext<>(
                        Optional.empty(), level, generator, random, pos, oreConfig))) {
                    placedAny = true;
                }
            }
        }

        return placedAny;
    }

    /**
     * 本分组在放置矿物之前需要先清掉的方块集合。
     * <p>
     * 集合为空时（默认情况：没有接管任何模组矿物、也没关大型矿脉）直接短路，
     * 不会为每个区块多做一次扫描。
     */
    private Set<Block> stripSet() {
        Set<Block> set = new HashSet<>();
        // 大型矿脉只存在于主世界一类的地形里，所以在主世界分组里顺手清理掉
        if (this.group == OreGroup.OVERWORLD) {
            set.addAll(OreVeins.blocksToStrip());
        }
        // 已被接管的模组矿物：先清掉其它模组生成的，再按本模组数值重新生成
        set.addAll(OreType.takeoverBlocks(this.group));
        return set;
    }
}
