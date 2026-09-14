package com.lingyao.veinrebirth;

import java.util.Locale;

/**
 * 矿物分组。
 * <p>
 * 每种分组对应一个已注册的 Feature / PlacedFeature（见 data/veinrebirth/worldgen/）。
 * 分组的"在哪些生物群系生成"由数据包里的生物群系标签决定：
 * <ul>
 *     <li>{@link #OVERWORLD} → data/veinrebirth/tags/worldgen/biome/overworld_ores.json（默认 #minecraft:is_overworld）</li>
 *     <li>{@link #EMERALD}   → data/veinrebirth/tags/worldgen/biome/emerald_ores.json（默认 #minecraft:is_mountain）</li>
 *     <li>{@link #NETHER}    → data/veinrebirth/tags/worldgen/biome/nether_ores.json（默认 #minecraft:is_nether）</li>
 *     <li>{@link #END}       → data/veinrebirth/tags/worldgen/biome/end_ores.json（默认 #minecraft:is_end）</li>
 * </ul>
 * 玩家可以用数据包覆盖上面这些标签，从而自由决定每种矿物在哪些生物群系生成。
 * <p>
 * 自动识别到的模组矿物只会被分到 {@link #OVERWORLD} / {@link #NETHER} / {@link #END} 三者之一；
 * {@link #EMERALD} 是原版绿宝石专用的分组（只在山地生成），不接收模组矿物。
 */
public enum OreGroup {

    /** 主世界矿物：煤炭、铜、铁、金、红石、钻石、青金石。 */
    OVERWORLD("overworld", "主世界"),
    /** 山地矿物：绿宝石（原版只在山地生成）。 */
    EMERALD("emerald", "山地"),
    /** 下界矿物：下界石英、下界金、远古残骸。 */
    NETHER("nether", "下界"),
    /** 末地矿物：原版没有，用于自动识别到的末地矿物。 */
    END("end", "末地");

    private final String id;
    private final String displayName;

    OreGroup(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return this.id;
    }

    /**
     * 按配置里的写法反查分组（overworld / nether / end）。
     * 认不出来时返回 null，调用方沿用自动判定的结果。
     */
    public static OreGroup byId(String id) {
        if (id == null) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (OreGroup group : values()) {
            if (group.id.equals(value)) {
                return group;
            }
        }
        return null;
    }

    /** 中文名，用于配置界面显示分组标题。 */
    public String displayName() {
        return this.displayName;
    }

    /** 该分组对应的已放置特征（PlacedFeature）在注册表中的路径。 */
    public String placedFeaturePath() {
        return this.id + "_ores";
    }

    /** 该分组对应的已配置特征（ConfiguredFeature）在注册表中的路径。 */
    public String configuredFeaturePath() {
        return "config_ore_" + this.id;
    }

    /** 该分组是否只服务原版内置矿物（不接收自动识别到的模组矿物）。 */
    public boolean vanillaOnly() {
        return this == EMERALD;
    }
}
