package com.lingyao.veinrebirth;

/**
 * 单个矿物的运行时数值设置。
 * <p>
 * 生成逻辑每生成一个区块都会重新读取这里的数值，所以修改后立刻对新生成的区块生效。
 */
public final class OreSettings {

    private final OreType type;

    private boolean enabled;
    private int count;
    private int size;
    private int weight;
    private int minY;
    private int maxY;

    public OreSettings(OreType type) {
        this.type = type;
        reset();
    }

    public OreType type() {
        return this.type;
    }

    /** 复制一份当前数值（解析配置时用临时副本，保证"要么整体生效、要么完全不动"）。 */
    public OreSettings copy() {
        OreSettings copy = new OreSettings(this.type);
        copy.enabled = this.enabled;
        copy.count = this.count;
        copy.size = this.size;
        copy.weight = this.weight;
        copy.minY = this.minY;
        copy.maxY = this.maxY;
        return copy;
    }

    /** 恢复为该矿物的默认值（原版矿物默认启用；识别到的模组矿物默认启用 = 不接管）。 */
    public void reset() {
        this.enabled = this.type.defaultEnabled();
        this.count = this.type.defaultCount();
        this.size = this.type.defaultSize();
        this.weight = this.type.defaultWeight();
        this.minY = this.type.defaultMinY();
        this.maxY = this.type.defaultMaxY();
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCount() {
        return this.count;
    }

    public void setCount(int count) {
        this.count = clamp(count, OreType.COUNT_MIN, OreType.COUNT_MAX);
    }

    public int getSize() {
        return this.size;
    }

    public void setSize(int size) {
        this.size = clamp(size, OreType.SIZE_MIN, OreType.SIZE_MAX);
    }

    public int getWeight() {
        return this.weight;
    }

    public void setWeight(int weight) {
        this.weight = clamp(weight, OreType.WEIGHT_MIN, OreType.WEIGHT_MAX);
    }

    public int getMinY() {
        return this.minY;
    }

    public void setMinY(int minY) {
        this.minY = clamp(minY, OreType.Y_MIN, OreType.Y_MAX);
    }

    public int getMaxY() {
        return this.maxY;
    }

    public void setMaxY(int maxY) {
        this.maxY = clamp(maxY, OreType.Y_MIN, OreType.Y_MAX);
    }

    /** 该矿物是否会在当前设置下真正生成。 */
    public boolean willGenerate() {
        return this.enabled && this.count > 0 && this.size > 0 && this.weight > 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
