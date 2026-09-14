package com.lingyao.veinrebirth;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * 矿物"替换目标"判定器集合。
 * <p>
 * 单独抽成一个类是为了避免静态初始化顺序问题（{@link OreType} 的静态字段构造时若直接引用本类的
 * 静态字段，这些字段可能还没有被初始化）。
 */
final class OreTargets {

    /** 普通石头（Y >= 0 时替换成普通矿物）。 */
    static final RuleTest STONE = new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES);
    /** 深层石头（Y < 0 时替换成深层矿物）。 */
    static final RuleTest DEEPSLATE = new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    /** 下界岩。 */
    static final RuleTest NETHERRACK = new BlockMatchTest(Blocks.NETHERRACK);
    /** 下界基础岩石（黑石 / 玄武岩 / 下界岩）。 */
    static final RuleTest BASE_STONE_NETHER = new TagMatchTest(BlockTags.BASE_STONE_NETHER);
    /** 末地石（识别到的末地矿物用）。 */
    static final RuleTest END_STONE = new BlockMatchTest(Blocks.END_STONE);

    private OreTargets() {
    }
}
