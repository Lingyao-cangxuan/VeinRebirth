package com.lingyao.veinrebirth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 矿脉重生的读写与运行时数值存储。
 * <p>
 * 配置文件是标准的 TOML 文本（config/veinrebirth-ores.toml），带中文注释，玩家可直接用记事本编辑，
 * 编辑后在游戏内执行 /veinrebirth reload 即可生效。
 * <p>
 * 文件结构：
 * <pre>
 *   [general]                  全局开关
 *   [coal] [copper] …          原版 11 种矿物（保持 1.0.x 的短 id，旧配置可直接沿用）
 *   [mekanism:tin_ore] …       自动识别到的其它模组矿物（用完整方块 id 作段名）
 *   [ore_veins]                原版大型矿脉开关
 * </pre>
 * 无法识别的段（例如某个模组被移除后残留的条目）会原样保留在文件末尾，不会被静默丢弃。
 */
public final class ConfigManager {

    public static final String FILE_NAME = "veinrebirth-ores.toml";

    /** "曾接管过"历史记录的文件名（与主配置同目录）。 */
    public static final String HANDLED_FILE_NAME = "veinrebirth-handled.txt";

    /** 配置段名：原版大型矿脉开关。 */
    public static final String VEIN_SECTION = "ore_veins";

    /** 配置段名：全局开关。 */
    public static final String GENERAL_SECTION = "general";

    /** 大型矿脉默认关闭，让本模组的数值完全说了算。 */
    public static final boolean DEFAULT_VEIN_ORES = false;

    /** 默认开启模组矿物自动识别。 */
    public static final boolean DEFAULT_DETECT_MODDED = true;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<OreType, OreSettings> SETTINGS = new LinkedHashMap<>();

    /** 无法识别的配置段，按"段名 -> 原文"保存，写回时原样输出。 */
    private static final Map<String, String> UNKNOWN_SECTIONS = new LinkedHashMap<>();

    /**
     * 玩家在配置文件里手写的维度覆盖（段名 -> 分组）。
     * <p>
     * 自动识别只能靠 Forge 的 {@code ores_in_ground} 标签和方块命名去猜矿物属于哪个维度，
     * 猜不准是常事——例如匠魂的钴矿既没打 {@code forge:ores_in_ground/netherrack} 标签，
     * 名字里也不含 nether，于是被当成主世界矿物，接管后就生成到了主世界。
     * 所以每种模组矿物的配置段里都带一行 {@code dimension}，玩家改成想要的维度即可覆盖自动判定。
     */
    private static final Map<String, OreGroup> DIMENSION_OVERRIDES = new LinkedHashMap<>();

    /**
     * 记录"曾经被本模组接管过"的模组矿物。
     * <p>
     * 为什么需要这个历史：{@code /veinrebirth refresh clean} 的清除范围原本等于
     * {@link OreType#handledBlocks()}，也就是「原版 11 种 + <b>当前</b>已接管的模组矿物」。
     * 于是出现一条死路——玩家先开启某种模组矿物的接管（本模组在世界里生成了一大批），
     * 之后又把它关掉（或点了"恢复默认"，模组矿物的默认值就是不接管），
     * 那种矿物就脱离了清理范围，先前生成的方块再也清不掉。
     * <p>
     * 一旦被接管过就记进这里，之后即使取消接管，{@code clean} 仍会清除它留下的方块。
     * 记录存在独立的 {@code config/veinrebirth-handled.txt}，不混进玩家编辑的主配置里。
     */
    private static final Set<String> EVER_HANDLED = new LinkedHashSet<>();

    private static Path configFile;
    private static boolean loaded;
    private static boolean veinOresEnabled = DEFAULT_VEIN_ORES;
    private static boolean detectModdedOres = DEFAULT_DETECT_MODDED;

    private ConfigManager() {
    }

    // ------------------------------------------------------------------ 全局开关

    /**
     * 是否保留原版「大型矿脉」（1.18+ 的铜矿脉 / 铁矿脉）。
     * <p>
     * 这套机制走地形噪声、不经过 placed_feature，无法用数据包移除，
     * 关闭后由 {@link OreVeins} 在矿物特征阶段清理掉。
     */
    public static boolean isVeinOresEnabled() {
        return veinOresEnabled;
    }

    public static void setVeinOresEnabled(boolean enabled) {
        veinOresEnabled = enabled;
    }

    /**
     * 是否自动识别其它模组添加的矿物。关闭后不再扫描，配置文件里遗留的模组矿物条目会被原样保留但不生效。
     * 改动需要重启游戏（识别发生在启动阶段）。
     */
    public static boolean isDetectModdedOres() {
        return detectModdedOres;
    }

    public static void setDetectModdedOres(boolean enabled) {
        detectModdedOres = enabled;
    }

    /** 配置文件路径：<游戏目录>/config/veinrebirth-ores.toml */
    public static Path file() {
        if (configFile == null) {
            configFile = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        }
        return configFile;
    }

    // ------------------------------------------------------------------ 接管历史

    /** "曾接管过"历史记录的文件路径（与主配置同目录）。 */
    public static Path handledFile() {
        return file().resolveSibling(HANDLED_FILE_NAME);
    }

    /** 曾接管过的模组矿物 id（配置段名，如 {@code mekanism:tin_ore}）。 */
    public static Set<String> everHandled() {
        return Collections.unmodifiableSet(EVER_HANDLED);
    }

    /**
     * 把当前所有处于"已接管"状态的模组矿物记进历史并落盘。
     * <p>
     * 挂在 {@link #save()} 和 {@link #reloadAfterDiscovery()} 之后执行，所以不论玩家是通过
     * 界面、命令还是直接手改配置文件开启的接管，都会被记下来，不会漏掉清理依据。
     */
    public static void rememberHandled() {
        boolean changed = false;
        for (OreType type : OreType.values()) {
            if (type.modded() && get(type).isEnabled() && EVER_HANDLED.add(type.id())) {
                changed = true;
            }
        }
        if (changed) {
            saveHandled();
        }
    }

    /** 清空接管历史（仅供自检使用）。 */
    static void clearHandled() {
        EVER_HANDLED.clear();
    }

    /** 从磁盘读入接管历史（自检也会直接调用）。 */
    static void loadHandled() {
        Path path = handledFile();
        try {
            if (!Files.exists(path)) {
                return;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    EVER_HANDLED.add(trimmed);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[VeinRebirth] Failed to read handled history, continuing with empty set", e);
        }
    }

    private static void saveHandled() {
        try {
            Path path = handledFile();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# 本模组曾经接管过的其它模组矿物（/veinrebirth refresh clean 的依据）。\n");
            sb.append("# 某种矿物一旦被接管过，即使之后又关掉接管，clean 仍会清除它先前生成的方块——\n");
            sb.append("# 否则那些残留会永久留在世界里再也清不掉。请不要手工删除本文件。\n");
            for (String id : EVER_HANDLED) {
                sb.append(id).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[VeinRebirth] Failed to save handled history", e);
        }
    }

    // ------------------------------------------------------------------ 数值存取

    /**
     * 该矿物在配置文件里被显式写下的维度；没有写就返回 null，表示沿用自动判定的结果。
     * <p>
     * 识别流程会先查这个覆盖值，再退回自动判定，所以玩家手改的维度不会在下次扫描时被冲掉。
     */
    public static OreGroup dimensionOverride(String configId) {
        return DIMENSION_OVERRIDES.get(configId);
    }

    /**
     * 记下某个模组矿物的维度覆盖并立刻生效（导入分享码时用）。
     * <p>
     * 走的是与配置文件里手写 {@code dimension = ...} 完全相同的一条路径，
     * 所以导入后 {@link #save()} 写出来的文件里一样会有这一行。
     */
    public static void applyDimensionOverride(String configId, OreGroup group) {
        if (configId == null || group == null) {
            return;
        }
        DIMENSION_OVERRIDES.put(configId, group);
        OreType.applyGroup(configId, group);
    }

    /** 取得某种矿物的运行时设置对象（可直接修改，生成时即时生效）。 */
    public static OreSettings get(OreType type) {
        OreSettings settings = SETTINGS.get(type);
        if (settings == null) {
            settings = new OreSettings(type);
            SETTINGS.put(type, settings);
        }
        return settings;
    }

    /** 为所有已登记的矿物准备好设置对象（识别到新矿物后调用）。 */
    public static void ensureSettingsForAll() {
        for (OreType type : OreType.values()) {
            get(type);
        }
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    // ------------------------------------------------------------------ 读写

    /** 读取配置文件；文件不存在时生成一份默认配置。 */
    public static synchronized void load() {
        ensureSettingsForAll();
        loadHandled();
        Path path = file();
        try {
            if (Files.exists(path)) {
                applyFromText(Files.readString(path, StandardCharsets.UTF_8));
                loaded = true;
                LOGGER.info("[VeinRebirth] Loaded ore config: {}", path);
            } else {
                loaded = true;
                save();
                LOGGER.info("[VeinRebirth] No config found, wrote default config: {}", path);
            }
        } catch (Exception e) {
            loaded = true;
            LOGGER.error("[VeinRebirth] Failed to read config, keeping current values", e);
        }
        // 配置文件里手写开启的接管也要记进历史，否则那些方块同样清不掉
        rememberHandled();
    }

    /**
     * 识别到新矿物后重新解析配置文件。
     * <p>
     * 首次 {@link #load()} 时这些矿物还没被登记，它们的配置段会被当成"无法识别"原样留存；
     * 识别完成后再解析一次，之前保存过的数值就能正确读进来，不会退化成默认值。
     */
    public static synchronized void reloadAfterDiscovery() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                applyFromText(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.error("[VeinRebirth] Failed to reload config after discovery", e);
        }
        // 识别完成后才可能读到模组矿物的 enabled，这里补记一次
        rememberHandled();
    }

    /** 把当前数值写回配置文件（整个文件按模板重写，注释会一并重新生成）。 */
    public static synchronized void save() {
        try {
            Path path = file();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, serialize(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[VeinRebirth] Failed to save config", e);
        }
        // 保存时顺手把当前已接管的矿物记进历史：这是清理残留的唯一依据
        rememberHandled();
    }

    /** 全部恢复为默认值。 */
    public static void resetToDefaults() {
        for (OreType type : OreType.values()) {
            get(type).reset();
        }
        veinOresEnabled = DEFAULT_VEIN_ORES;
    }

    /**
     * 把当前配置广播给所有在线玩家。
     * 单人游戏里两侧共用同一份数值，这里主要是让多人游戏里每个玩家的界面与服务端保持一致。
     */
    public static void syncToAll() {
        try {
            NetworkHandler.broadcast(new ConfigSyncPacket(serialize()));
        } catch (Exception e) {
            LOGGER.debug("[VeinRebirth] Failed to broadcast config: {}", e.getMessage());
        }
    }

    /**
     * 按文本内容更新数值。解析在临时副本上完成，成功后才整体替换，
     * 因此即使文本内容有问题也不会破坏当前数值。
     */
    public static synchronized void applyFromText(String text) {
        ParseState state = new ParseState();
        for (OreType type : OreType.values()) {
            OreSettings existing = SETTINGS.get(type);
            state.ores.put(type, existing == null ? new OreSettings(type) : existing.copy());
        }
        parseInto(text, state);
        SETTINGS.clear();
        SETTINGS.putAll(state.ores);
        veinOresEnabled = state.veinOres;
        detectModdedOres = state.detectModded;
        UNKNOWN_SECTIONS.clear();
        UNKNOWN_SECTIONS.putAll(state.unknown);
        DIMENSION_OVERRIDES.clear();
        DIMENSION_OVERRIDES.putAll(state.dimensionOverrides);
        // 把玩家手写的维度应用到已经登记过的矿物上（自动判定可能已经先给出过一个分组）
        for (Map.Entry<String, OreGroup> entry : DIMENSION_OVERRIDES.entrySet()) {
            OreType.applyGroup(entry.getKey(), entry.getValue());
        }
        loaded = true;
    }

    /** 解析过程中的临时容器，保证"要么整体生效、要么完全不动"。 */
    private static final class ParseState {
        final Map<OreType, OreSettings> ores = new LinkedHashMap<>();
        final Map<String, String> unknown = new LinkedHashMap<>();
        final Map<String, OreGroup> dimensionOverrides = new LinkedHashMap<>();
        boolean veinOres = DEFAULT_VEIN_ORES;
        boolean detectModded = DEFAULT_DETECT_MODDED;
    }

    private static void parseInto(String text, ParseState state) {
        if (text == null) {
            return;
        }
        OreSettings working = null;
        boolean inVeinSection = false;
        boolean inGeneralSection = false;
        String unknownName = null;
        StringBuilder unknownText = new StringBuilder();

        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1).trim();
            }
            // 判断结构时先去掉行尾注释
            String structural = line;
            int hash = structural.indexOf('#');
            if (hash >= 0) {
                structural = structural.substring(0, hash).trim();
            }

            if (structural.startsWith("[") && structural.endsWith("]")) {
                // 收尾上一个无法识别的段
                if (unknownName != null) {
                    state.unknown.put(unknownName, unknownText.toString());
                    unknownName = null;
                    unknownText.setLength(0);
                }
                String section = structural.substring(1, structural.length() - 1).trim();
                if (section.length() >= 2 && section.charAt(0) == '"'
                        && section.charAt(section.length() - 1) == '"') {
                    section = section.substring(1, section.length() - 1);
                }
                inVeinSection = VEIN_SECTION.equalsIgnoreCase(section);
                inGeneralSection = GENERAL_SECTION.equalsIgnoreCase(section);
                OreType type = (inVeinSection || inGeneralSection) ? null : OreType.byId(section);
                working = type == null ? null : state.ores.computeIfAbsent(type, OreSettings::new);
                if (!inVeinSection && !inGeneralSection && working == null) {
                    unknownName = section;
                }
                continue;
            }

            if (structural.isEmpty()) {
                continue;
            }
            int eq = structural.indexOf('=');
            String key = eq < 0 ? null : structural.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? null : structural.substring(eq + 1).trim();

            if (unknownName != null) {
                unknownText.append(raw).append('\n');
                // 首次启动时识别扫描还没跑，模组矿物的段在这里是"无法识别"的，
                // 但玩家手写的 dimension 必须照样记下来，否则下一次扫描就会把它冲掉
                if ("dimension".equals(key)) {
                    OreGroup override = OreGroup.byId(value);
                    if (override != null) {
                        state.dimensionOverrides.put(unknownName, override);
                    }
                }
                continue;
            }

            if (key == null) {
                continue;
            }

            if (inVeinSection) {
                if ("enabled".equals(key)) {
                    state.veinOres = parseBool(value, state.veinOres);
                }
                continue;
            }
            if (inGeneralSection) {
                if ("detect_modded_ores".equals(key)) {
                    state.detectModded = parseBool(value, state.detectModded);
                }
                continue;
            }
            if (working == null) {
                continue;
            }
            try {
                switch (key) {
                    case "enabled" -> working.setEnabled(parseBool(value, working.isEnabled()));
                    case "count" -> working.setCount((int) Double.parseDouble(value));
                    case "size" -> working.setSize((int) Double.parseDouble(value));
                    case "weight" -> working.setWeight((int) Double.parseDouble(value));
                    case "min_y" -> working.setMinY((int) Double.parseDouble(value));
                    case "max_y" -> working.setMaxY((int) Double.parseDouble(value));
                    case "dimension" -> {
                        OreGroup override = OreGroup.byId(value);
                        if (override != null) {
                            state.dimensionOverrides.put(working.type().id(), override);
                        }
                    }
                    default -> {
                        // 未知键忽略
                    }
                }
            } catch (NumberFormatException ignored) {
                // 数值非法则保留该字段的默认值
            }
        }

        if (unknownName != null) {
            state.unknown.put(unknownName, unknownText.toString());
        }
    }

    /** 兼容 1/0、on/off、yes/no 等写法，无法识别时保留原值。 */
    private static boolean parseBool(String value, boolean fallback) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "1", "on", "yes" -> true;
            case "false", "0", "off", "no" -> false;
            default -> fallback;
        };
    }

    // ------------------------------------------------------------------ 序列化

    /** 生成完整的配置文件文本（含中文注释）。 */
    public static String serialize() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder(16384);
        sb.append(HEADER);
        sb.append(generalSection());
        for (OreType type : OreType.values()) {
            if (!type.modded()) {
                sb.append(oreSection(type, false));
            }
        }
        appendModdedSections(sb);
        appendUnknownSections(sb);
        sb.append(veinSection());
        return sb.toString();
    }

    private static String generalSection() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('\n');
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append("# 全局开关\n");
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append("#  detect_modded_ores  是否自动识别其它模组添加的矿物。\n");
        sb.append("#      true  = 启动时扫描方块注册表与 forge:ores 标签，把识别到的矿石（例如\n");
        sb.append("#              通用机械的锡矿石、热力系列的银矿石）一起列进配置界面与配置文件。\n");
        sb.append("#      false = 不识别。只有原版 11 种矿物可用；配置文件里遗留的模组矿物条目\n");
        sb.append("#              会原样保留但不再生效。\n");
        sb.append("#      注意：本项改动需要重启游戏才会生效。\n");
        sb.append("#\n");
        sb.append("#  识别到的矿物默认 enabled = false，也就是「不接管」——本模组完全不碰它，\n");
        sb.append("#  保持其它模组自己的生成方式。把它改成 true 才会接管：先清除其它模组生成的\n");
        sb.append("#  这种矿物，再按下面 count / size / weight / min_y / max_y 的数值重新生成，\n");
        sb.append("#  这样该矿物的总产量才真正由本模组说了算（不会出现两份叠加）。\n");
        sb.append("#  关掉（enabled = false）则还原为其它模组自己的生成。\n");
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append('[').append(GENERAL_SECTION).append("]\n");
        sb.append("detect_modded_ores = ").append(detectModdedOres).append('\n');
        return sb.toString();
    }

    private static String oreSection(OreType type, boolean modded) {
        OreSettings settings = get(type);
        StringBuilder sb = new StringBuilder(256);
        sb.append('\n');
        sb.append("# -----------------------------------------------------------------------------\n");
        if (modded) {
            sb.append("# ").append(type.blockId()).append(groupHint(type)).append('\n');
        } else {
            sb.append("# ").append(type.displayName()).append(groupHint(type)).append('\n');
        }
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append('[').append(type.id()).append("]\n");
        if (modded) {
            sb.append("# 维度：这种矿物在哪个维度生成。本模组按 Forge 标签和方块名自动判定，\n");
            sb.append("# 判定不准时把下面这行改成 overworld / nether / end 即可覆盖（改完不需要重启）。\n");
            sb.append("dimension = ").append(type.group() == OreGroup.EMERALD
                    ? OreGroup.OVERWORLD.id() : type.group().id()).append('\n');
            sb.append('\n');
        }
        sb.append("enabled = ").append(settings.isEnabled()).append('\n');
        sb.append('\n');
        sb.append("count   = ").append(settings.getCount()).append('\n');
        sb.append("size    = ").append(settings.getSize()).append('\n');
        sb.append("weight  = ").append(settings.getWeight()).append('\n');
        sb.append('\n');
        sb.append("min_y   = ").append(settings.getMinY()).append('\n');
        sb.append("max_y   = ").append(settings.getMaxY()).append('\n');
        return sb.toString();
    }

    private static void appendModdedSections(StringBuilder sb) {
        OreType[] types = OreType.values();
        boolean hasModded = false;
        for (OreType type : types) {
            if (type.modded()) {
                hasModded = true;
                break;
            }
        }
        if (!hasModded) {
            return;
        }
        int total = OreType.moddedCount();
        int takenOver = 0;
        for (OreType type : types) {
            if (type.modded() && get(type).isEnabled()) {
                takenOver++;
            }
        }
        sb.append('\n');
        sb.append("# =============================================================================\n");
        sb.append("#  以下为自动识别到的其它模组矿物（共 ").append(total).append(" 种，其中 ")
                .append(takenOver).append(" 种已被本模组接管）\n");
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append("#  它们的数值含义与上面的原版矿物完全一致。默认 enabled = false 表示\n");
        sb.append("#  「不接管」：保持其它模组自己的生成，本模组不生成这种矿物。\n");
        sb.append("#  改成 enabled = true 才会接管（先清除其它模组的生成，再按下面的数值重新生成）。\n");
        sb.append("#  高度区间 min_y / max_y 是按矿物名称给的保底值，不一定符合原模组的设定，\n");
        sb.append("#  不满意的话直接改，或者在游戏内配置界面拖滑块。\n");
        sb.append("#  维度 dimension 是按 Forge 标签与方块名自动判定的，判定错了直接改这一行，\n");
        sb.append("#  改成 overworld / nether / end 即可（不是所有模组都会正确填写标签，\n");
        sb.append("#  例如匠魂的钴矿就既没有标签、名字里也不含 nether）。\n");
        sb.append("# =============================================================================\n");
        for (OreType type : types) {
            if (type.modded()) {
                sb.append(oreSection(type, true));
            }
        }
    }

    private static void appendUnknownSections(StringBuilder sb) {
        boolean any = false;
        for (Map.Entry<String, String> entry : UNKNOWN_SECTIONS.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        sb.append('\n');
        sb.append("# =============================================================================\n");
        sb.append("#  以下配置段本模组无法识别（通常是某个模组被移除后残留的矿物条目，\n");
        sb.append("#  或者关闭了 detect_modded_ores 时遗留的条目）。原样保留，不会被丢弃。\n");
        sb.append("# =============================================================================\n");
        for (Map.Entry<String, String> entry : UNKNOWN_SECTIONS.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            sb.append('[').append(entry.getKey()).append("]\n");
            sb.append(entry.getValue());
            if (!entry.getValue().endsWith("\n")) {
                sb.append('\n');
            }
        }
    }

    private static String veinSection() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('\n');
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append("# 原版大型矿脉（1.18+ 新增机制，独立于上面的矿物簇）\n");
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append("#  1.18 起原版加入了一套「大型矿脉」：成片的花岗岩里嵌着铜矿石和粗铜块，\n");
        sb.append("#  成片的凝灰岩里嵌着铁矿石和粗铁块，生成在 Y ").append(OreVeins.MIN_Y)
                .append(" ~ ").append(OreVeins.MAX_Y).append(" 之间。\n");
        sb.append("#  它由地形噪声直接写入方块，不经过 placed_feature，所以无法用数据包移除，\n");
        sb.append("#  由本模组在矿物生成阶段统一处理。\n");
        sb.append("#\n");
        sb.append("#  enabled = true   保留大型矿脉（和原版一样）。此时铜、铁的最终数量会明显\n");
        sb.append("#                   多于上面设置的 count，因为矿脉是额外叠加的。\n");
        sb.append("#  enabled = false  清除所有大型矿脉，上面的 count / size / weight 完全说了算。\n");
        sb.append("#                   （被清掉的位置会补回原处的花岗岩 / 凝灰岩 / 石头，地形不变）\n");
        sb.append("#\n");
        sb.append("#  另外：不管这里怎么设，只要上面某一种矿物 enabled = false，\n");
        sb.append("#        它对应的矿脉方块也会一并清除，保证「关掉 = 真的没有」。\n");
        sb.append("#  已生成过的区块同样需要用 /veinrebirth refresh <半径> 清一次。\n");
        sb.append("# -----------------------------------------------------------------------------\n");
        sb.append('[').append(VEIN_SECTION).append("]\n");
        sb.append("enabled = ").append(veinOresEnabled).append('\n');
        return sb.toString();
    }

    private static String groupHint(OreType type) {
        return switch (type.group()) {
            case OVERWORLD -> "（主世界）";
            case EMERALD -> "（只在山地生物群系生成）";
            case NETHER -> "（下界）";
            case END -> "（末地）";
        };
    }

    private static final String HEADER = """
            #===============================================================================
            #  VeinRebirth  矿脉重生
            #-------------------------------------------------------------------------------
            #  本文件由模组自动生成。可以直接用记事本 / VSCode 等编辑，保存后在游戏内执行：
            #
            #      /veinrebirth reload          重新读取本文件
            #      /veinrebirth refresh 4       让周围 4 个区块范围内已生成的区块重新生成矿物
            #      /veinrebirth refresh 4 clean 先清除该范围内已有矿物、再重新生成
            #      /veinrebirth list            列出所有已登记的矿物（含自动识别到的模组矿物）
            #      /veinrebirth info <矿物id>   查看某种矿物的当前数值
            #      /veinrebirth preset          列出内置预设方案（原版体验 / 富矿世界 / …）
            #      /veinrebirth preset <名称>   一键套用某个预设
            #      /veinrebirth share           生成分享码（点击复制，同时写入 share.txt）
            #      /veinrebirth share import <码|file>  从分享码或文件导入整套数值
            #      /veinrebirth veins           查看原版大型矿脉的开关状态
            #      /veinrebirth save            把游戏内当前数值写回本文件
            #      /veinrebirth selftest        自检：确认各种矿物挂在正确的岩层上（排查用）
            #
            #  也可以完全不改文件：游戏内「模组列表 -> 矿脉重生 -> 配置」有中文界面，
            #  左侧是可滚动的矿物列表（带搜索框），右侧拖动滑块即时生效，
            #  点「保存配置」即可写回本文件；右下角「预设方案 / 分享码」里可以一键换风格、
            #  或者把整套数值生成分享码发给别人。
            #
            #-------------------------------------------------------------------------------
            #  字段说明（参照《我的世界》1.12.2 自定义世界里的矿物设置）
            #
            #    enabled  是否生成该矿物。
            #             false = 完全不生成（注意：原版矿物已被本模组移除，关掉就是彻底没有）
            #             对自动识别到的模组矿物，false = 不接管（保持其它模组自己的生成）
            #
            #    count    生成数量：每个区块尝试生成的矿脉条数。
            #             对应 1.12.2 自定义世界里的矿物"生成数量 / 生成几率"，数值越大矿越多。
            #             0 = 不生成。建议范围 0 ~ 40。
            #
            #    size     生成规模：单条矿脉的方块数量，越大矿脉越粗大。
            #             对应 1.12.2 里的"生成规模"。1 = 单个方块，原版多为 3 ~ 20。
            #
            #    weight   生成权重：每条矿脉的生成成功率（百分比）。
            #             100 = 必定生成，50 = 大约一半的矿脉会生成，0 = 不生成。
            #             用来做"稀有矿物"很方便：count 保持原样，把 weight 调低即可。
            #
            #    min_y    生成高度下限（绝对 Y 坐标，可以是负数）
            #    max_y    生成高度上限（绝对 Y 坐标，可以是负数）
            #             指 1.12.2 里的"最低高度 / 最高高度"，范围内均匀随机分布。
            #             主世界 Y 范围 -64 ~ 320，下界 0 ~ 127，超出范围会自动截断。
            #
            #-------------------------------------------------------------------------------
            #  其它说明
            #
            #   1. 修改后"新生成"的区块立刻生效；已经生成过的区块需要用
            #      /veinrebirth refresh <半径> 刷新（只影响周围区块，半径上限 8）。
            #      refresh 只能"补加"矿物；若要把已有矿物也换成新数值，
            #      请用 /veinrebirth refresh <半径> clean（会清除范围内所有矿物方块后重建，
            #      该范围内的矿物将被重置，操作不可撤销，请先备份存档）。
            #
            #   2. 原版矿物已被本模组整体接管，替换范围：
            #      主世界：煤炭、铜、铁、金、红石、钻石、青金石、绿宝石
            #      下界　：下界石英、下界金、远古残骸
            #      （泥土、沙砾、安山岩、闪长岩、花岗岩、凝灰岩等非矿物簇仍为原版生成）
            #
            #   3. 其它模组添加的矿物由本模组自动识别（见 [general] 与文件后段的模组矿物区块）。
            #      识别到的矿物默认不接管，需要手动打开。
            #
            #   4. 原版 1.18+ 还有一套独立的"大型矿脉"（见文件末尾 [ore_veins]），
            #      它不经过数据包，无法用 biome_modifier 移除，必须靠本模组清理。
            #      默认已关闭；想还原原版手感把它改成 true 即可。
            #
            #   5. 想让矿物在哪些生物群系生成，由数据包标签决定（可用数据包覆盖）：
            #      主世界：veinrebirth:overworld_ores  默认 #minecraft:is_overworld
            #      绿宝石：veinrebirth:emerald_ores    默认 #minecraft:is_mountain
            #      下界　：veinrebirth:nether_ores     默认 #minecraft:is_nether
            #      末地　：veinrebirth:end_ores        默认 #minecraft:is_end
            #
            #   6. 若想"原版矿物 + 本模组矿物同时生成"（叠加），可用数据包覆盖并删除
            #      data/veinrebirth/forge/biome_modifier/remove_vanilla_*.json 中的条目。
            #      （注意：大型矿脉不在此列，它不受 remove_features 影响）
            #===============================================================================
            """;
}
