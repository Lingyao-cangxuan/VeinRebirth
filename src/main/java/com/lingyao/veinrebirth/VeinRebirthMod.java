package com.lingyao.veinrebirth;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.lingyao.veinrebirth.client.ClientSetup;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 矿脉重生模组主类。
 * <p>
 * 注册每种分组对应的 Feature 类型（veinrebirth:config_ore_overworld / _emerald / _nether / _end），
 * 加载配置文件，自动识别其它模组的矿物，初始化网络同步，并在客户端注册模组配置界面。
 */
@Mod(VeinRebirthMod.MOD_ID)
public class VeinRebirthMod {

    public static final String MOD_ID = "veinrebirth";

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, MOD_ID);

    /** 分组 -> 已注册特征实例，供 /veinrebirth refresh 直接调用。 */
    public static final Map<OreGroup, RegistryObject<ConfigOreFeature>> ORE_FEATURES =
            new EnumMap<>(OreGroup.class);

    static {
        for (OreGroup group : OreGroup.values()) {
            ORE_FEATURES.put(group, FEATURES.register(group.configuredFeaturePath(),
                    () -> new ConfigOreFeature(group)));
        }
    }

    public VeinRebirthMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        FEATURES.register(modBus);

        // 读取 / 生成 config/veinrebirth-ores.toml
        ConfigManager.load();

        // 自动识别其它模组添加的矿物。放在 common setup 是因为此时方块才全部注册完毕；
        // enqueueWork 把它排到主线程，避免与其它模组的并行初始化抢注册表。
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(OreDiscoverer::discoverInitial));

        // 客户端与服务端之间的配置同步（多人游戏用；单人游戏里两侧共用同一份数值）
        NetworkHandler.register();

        // 客户端专属：在「模组列表 -> 配置」里挂上中文界面
        // 注意：这里用 dist 判断 + 独立的客户端类，避免专用服务端加载客户端类而崩溃
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.registerConfigScreen();
        }
    }
}
