package com.lingyao.veinrebirth;

import java.util.List;
import java.util.Locale;

/**
 * 内置参数预设方案：一键把整套数值换成某种风格。
 * <p>
 * 设计上有三个刻意的约定：
 * <ul>
 *     <li><b>以「出厂默认值」为基准做倍率，而不是以当前值为基准。</b>
 *         所以反复点同一个预设结果是一样的（「富矿世界」点两次不会变成 4 倍）。
 *         代价是：你手动调过的 count / size / weight 会被预设覆盖掉——预设的语义就是
 *         「把整套数值换成这种风格」，不是「在当前基础上增减」。想微调请用界面上的滑块。</li>
 *     <li><b>不碰「未启用」的矿物。</b>预设只调整当前真正在生成的矿物；自动识别到的模组矿物
 *         默认 {@code enabled = false}（不接管），因此不会被预设意外打开或改写。
 *         唯一的例外是 {@link #VANILLA}——它的语义就是「恢复出厂」，本来就该连开关一起重置。</li>
 *     <li><b>不改维度。</b>矿物在哪个维度生成与「矿量风格」无关，手写过的
 *         {@code dimension} 覆盖会原样保留。</li>
 * </ul>
 */
public enum Preset {

    /** 原版体验：完全恢复出厂数值与开关。 */
    VANILLA("vanilla", "原版体验",
            "恢复出厂数值与开关（含取消模组矿物的接管），保留原版大型矿脉",
            1.0D, 1.0D, 1.0D, -1, true),

    /** 富矿世界：比原版充裕，但还没到作弊的程度。 */
    RICH("rich", "富矿世界",
            "数量 ×2、规模 ×1.5，保留矿脉——矿比原版多，但地形观感不变",
            2.0D, 1.5D, 1.0D, -1, true),

    /** 超级富矿：想要什么挖什么。 */
    LUSH("lush", "超级富矿",
            "数量 ×4、规模 ×2、权重 100%，保留矿脉——挖到哪里都是矿",
            4.0D, 2.0D, 1.0D, 100, true),

    /** 巨型矿脉：少而巨大。 */
    GIANT("giant", "巨型矿脉",
            "数量减半、规模 ×3、权重 100%——矿脉少但巨大，一条挖到底",
            0.5D, 3.0D, 1.0D, 100, true),

    /** 贫矿生存：硬核档。 */
    SCARCE("scarce", "贫矿生存",
            "数量减半、规模 ×0.7、权重 ×0.7，并清除原版大型矿脉——矿要省着用",
            0.5D, 0.7D, 0.7D, -1, false);

    /** 供命令 tab 补全与列表展示使用。 */
    public static final List<String> IDS = List.of(
            VANILLA.id, RICH.id, LUSH.id, GIANT.id, SCARCE.id);

    private final String id;
    private final String displayName;
    private final String description;

    private final double countMul;
    private final double sizeMul;
    private final double weightMul;

    /** 强制设定的权重；&lt;= 0 表示按 {@link #weightMul} 缩放。 */
    private final int forceWeight;

    /** 套用后原版大型矿脉的开关状态。 */
    private final boolean veinOres;

    Preset(String id, String displayName, String description,
            double countMul, double sizeMul, double weightMul, int forceWeight, boolean veinOres) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.countMul = countMul;
        this.sizeMul = sizeMul;
        this.weightMul = weightMul;
        this.forceWeight = forceWeight;
        this.veinOres = veinOres;
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    public boolean veinOres() {
        return this.veinOres;
    }

    /** 按 id 或显示名查找预设，找不到返回 null。 */
    public static Preset byId(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        for (Preset preset : values()) {
            if (preset.id.equals(key) || preset.displayName.equals(value.trim())) {
                return preset;
            }
        }
        return null;
    }

    /**
     * 套用预设：改写运行时数值，并把矿脉开关一起设好。
     * <p>
     * 调用方负责随后 {@link ConfigManager#save()} 与同步到客户端。
     */
    public static void apply(Preset preset) {
        if (preset == null) {
            return;
        }
        for (OreType type : OreType.values()) {
            OreSettings settings = ConfigManager.get(type);
            if (preset == VANILLA) {
                // 「恢复出厂」就该连启用状态一起重置
                settings.reset();
                continue;
            }
            if (!settings.isEnabled()) {
                // 没启用的矿物（含默认不接管的模组矿物）不动
                continue;
            }
            // 基准是出厂默认值，不是当前值 —— 这样预设是幂等的
            OreSettings base = new OreSettings(type);
            settings.setCount(scale(base.getCount(), preset.countMul));
            settings.setSize(scale(base.getSize(), preset.sizeMul));
            // 权重 0 表示「不生成」，与 count / size 同一规则：基准为 0 时保持 0
            settings.setWeight(base.getWeight() <= 0
                    ? 0
                    : (preset.forceWeight > 0
                            ? preset.forceWeight
                            : scale(base.getWeight(), preset.weightMul)));
        }
        ConfigManager.setVeinOresEnabled(preset.veinOres);
    }

    /**
     * 整数倍率换算。
     * <p>
     * 原值为 0 时保持 0（0 表示「不生成」，缩放它没有意义）；
     * 否则结果至少为 1——「减半」不应该把 1 变成 0 而让整种矿物消失。
     */
    private static int scale(int base, double mul) {
        if (base <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(base * mul));
    }
}
