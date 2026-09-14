package com.lingyao.oreprobe;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 仅供开发期验证使用的小模组：注册若干"假矿石方块"，模拟整合包里第三方模组添加的矿石，
 * 用来检验 VeinRebirth 的模组矿物自动识别是否正常工作。
 * <p>
 * 覆盖的命名形态：
 * <ul>
 *     <li>{@code tin_ore} + {@code deepslate_tin_ore} —— 前缀式深板岩变体（原版风格，最常见）</li>
 *     <li>{@code lead_deepslate_ore} —— 后缀式深板岩变体（部分模组使用）</li>
 *     <li>{@code silver_ore} —— 只有一种形态</li>
 *     <li>{@code nether_sulfur_ore} —— 下界矿物（靠名称关键字判维度）</li>
 *     <li>{@code end_platinum_ore} —— 末地矿物</li>
 *     <li>{@code cobalt_ore} —— <b>下界矿物但不含任何维度关键词、也不打 ores_in_ground 标签</b>，
 *         复现匠魂钴矿被判成主世界矿物的场景</li>
 *     <li>{@code crystal_deposit} —— 不按 _ore 命名，但打了 forge:ores 标签（只能靠标签识别）</li>
 * </ul>
 * 本模组不生成任何矿物，只注册方块与标签。
 */
@Mod(OreProbe.MOD_ID)
public class OreProbe {

    public static final String MOD_ID = "oreprobe";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);

    public static final RegistryObject<Block> TIN_ORE = BLOCKS.register("tin_ore", OreProbe::ore);
    public static final RegistryObject<Block> DEEPSLATE_TIN_ORE = BLOCKS.register("deepslate_tin_ore", OreProbe::ore);
    public static final RegistryObject<Block> LEAD_DEEPSLATE_ORE = BLOCKS.register("lead_deepslate_ore", OreProbe::ore);
    public static final RegistryObject<Block> SILVER_ORE = BLOCKS.register("silver_ore", OreProbe::ore);
    public static final RegistryObject<Block> NETHER_SULFUR_ORE = BLOCKS.register("nether_sulfur_ore", OreProbe::ore);
    public static final RegistryObject<Block> END_PLATINUM_ORE = BLOCKS.register("end_platinum_ore", OreProbe::ore);
    public static final RegistryObject<Block> COBALT_ORE = BLOCKS.register("cobalt_ore", OreProbe::ore);
    public static final RegistryObject<Block> CRYSTAL_DEPOSIT = BLOCKS.register("crystal_deposit", OreProbe::ore);

    public OreProbe() {
        BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    private static Block ore() {
        return new Block(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(3.0F, 3.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops());
    }
}
