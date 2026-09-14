package com.lingyao.veinrebirth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

/**
 * 矿物种类定义（含中文名与原版默认数值）。
 * <p>
 * 1.1.0 起由「固定枚举」改为「动态注册表」：原版 11 种矿物在类加载时静态登记，
 * 其它模组添加的矿物由 {@link OreDiscoverer} 在运行时扫描后追加进来，
 * 因此配置界面与配置文件都能覆盖这些后加入的矿物。
 * <p>
 * 为了保持配置文件向后兼容，原版矿物使用短 id（{@code coal} / {@code copper} …），
 * 识别到的模组矿物使用完整方块 id（{@code mekanism:tin_ore}）。
 */
public final class OreType {

    /** 滑块 / 配置允许的取值区间。 */
    public static final int COUNT_MIN = 0, COUNT_MAX = 200;
    public static final int SIZE_MIN = 1, SIZE_MAX = 64;
    public static final int WEIGHT_MIN = 0, WEIGHT_MAX = 100;
    public static final int Y_MIN = -128, Y_MAX = 512;

    /** 识别到的模组矿物在玩家主动接管前的初始数值。 */
    public static final int MODDED_DEFAULT_COUNT = 4;
    public static final int MODDED_DEFAULT_SIZE = 9;
    public static final int MODDED_DEFAULT_WEIGHT = 100;

    /** 注册顺序（原版在前，识别到的模组矿物按扫描顺序追加）。 */
    private static final List<OreType> ORDERED = new ArrayList<>();

    /** 配置段名 -> 矿物。 */
    private static final Map<String, OreType> BY_ID = new LinkedHashMap<>();

    // ============================================================================
    //  原版内置矿物（id 保持短名，兼容 1.0.x 的旧配置文件）
    // ============================================================================

    // id, 中文名, 分组, 默认数量, 默认规模, 默认权重, 默认最低Y, 默认最高Y, 方块...
    public static final OreType COAL = vanilla("coal", "煤炭", OreGroup.OVERWORLD, 20, 17, 100, 0, 192,
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore");
    public static final OreType COPPER = vanilla("copper", "铜", OreGroup.OVERWORLD, 16, 12, 100, -16, 112,
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore");
    public static final OreType IRON = vanilla("iron", "铁", OreGroup.OVERWORLD, 20, 9, 100, -64, 72,
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore");
    public static final OreType GOLD = vanilla("gold", "金", OreGroup.OVERWORLD, 4, 9, 100, -64, 32,
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore");
    public static final OreType REDSTONE = vanilla("redstone", "红石", OreGroup.OVERWORLD, 10, 8, 100, -64, 16,
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore");
    public static final OreType DIAMOND = vanilla("diamond", "钻石", OreGroup.OVERWORLD, 4, 8, 100, -64, 16,
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");
    public static final OreType LAPIS = vanilla("lapis", "青金石", OreGroup.OVERWORLD, 4, 7, 100, -64, 64,
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore");
    public static final OreType EMERALD = vanilla("emerald", "绿宝石", OreGroup.EMERALD, 8, 3, 100, -16, 320,
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore");
    public static final OreType QUARTZ = vanilla("quartz", "下界石英", OreGroup.NETHER, 16, 14, 100, 10, 117,
            "minecraft:nether_quartz_ore");
    public static final OreType NETHER_GOLD = vanilla("nether_gold", "下界金", OreGroup.NETHER, 10, 10, 100, 10, 117,
            "minecraft:nether_gold_ore");
    public static final OreType ANCIENT_DEBRIS = vanilla("ancient_debris", "远古残骸", OreGroup.NETHER, 2, 3, 100, 8, 119,
            "minecraft:ancient_debris");

    // ============================================================================

    private final String id;
    private final String fixedName;
    private final String namespace;
    private final boolean modded;
    private final boolean defaultEnabled;
    private final int defaultCount;
    private final int defaultSize;
    private final int defaultWeight;
    private final int defaultMinY;
    private final int defaultMaxY;
    private final List<String> blockIds;

    private OreGroup group;

    /** 懒加载：避免在静态初始化阶段就触碰方块注册表与方块标签。 */
    private List<OreConfiguration.TargetBlockState> targets;
    private Set<Block> oreBlocks;
    private List<Block> resolvedBlocks;

    private OreType(String id, String fixedName, OreGroup group, boolean modded,
            boolean defaultEnabled, int defaultCount, int defaultSize, int defaultWeight,
            int defaultMinY, int defaultMaxY, List<String> blockIds) {
        this.id = id;
        this.fixedName = fixedName;
        this.group = group;
        this.modded = modded;
        this.defaultEnabled = defaultEnabled;
        this.defaultCount = defaultCount;
        this.defaultSize = defaultSize;
        this.defaultWeight = defaultWeight;
        this.defaultMinY = defaultMinY;
        this.defaultMaxY = defaultMaxY;
        this.blockIds = List.copyOf(blockIds);
        this.namespace = namespaceOf(id);
    }

    private static OreType vanilla(String id, String name, OreGroup group,
            int count, int size, int weight, int minY, int maxY, String... blocks) {
        OreType type = new OreType(id, name, group, false, true,
                count, size, weight, minY, maxY, List.of(blocks));
        ORDERED.add(type);
        BY_ID.put(id, type);
        return type;
    }

    /**
     * 登记一种识别到的模组矿物；已登记过则只更新分组（标签加载后可能细化）并原样返回。
     *
     * @param configId 配置段名，等于主力方块的完整 id（如 {@code mekanism:tin_ore}）
     */
    static synchronized OreType registerModded(String configId, OreGroup group, List<String> blockIds,
            int count, int size, int weight, int minY, int maxY) {
        // 玩家在配置文件里手写过 dimension 就以玩家的为准：自动识别靠标签与命名猜测维度，
        // 猜不准很常见（匠魂的钴矿就是），不能让每次扫描都把玩家改好的结果冲掉。
        OreGroup override = ConfigManager.dimensionOverride(configId);
        if (override != null) {
            group = override;
        }
        OreType existing = BY_ID.get(configId);
        if (existing != null) {
            existing.group = group;
            return existing;
        }
        OreType type = new OreType(configId, null, group, true, false,
                count, size, weight, minY, maxY, blockIds);
        ORDERED.add(type);
        BY_ID.put(configId, type);
        return type;
    }

    /**
     * 按配置文件里手写的 dimension 更新某种矿物的分组。
     * <p>
     * 只认自动识别到的模组矿物——原版 11 种的维度是固定的（煤炭在主世界、石英在下界…），
     * 不允许被配置改写。
     */
    static synchronized void applyGroup(String configId, OreGroup group) {
        if (group == null) {
            return;
        }
        OreType type = BY_ID.get(configId);
        if (type != null && type.modded) {
            type.group = group;
        }
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    // ------------------------------------------------------------------ 查询

    /** 全部矿物（含识别到的模组矿物），顺序固定：原版在前。 */
    public static List<OreType> all() {
        return Collections.unmodifiableList(ORDERED);
    }

    /** 与枚举时代保持同样签名，方便既有调用点直接复用。 */
    public static OreType[] values() {
        return ORDERED.toArray(new OreType[0]);
    }

    /** 已登记的矿物总数（给每帧渲染的界面用，避免反复创建数组）。 */
    public static int registrySize() {
        return ORDERED.size();
    }

    /** 配置段名：原版为短名（coal），模组矿物为完整方块 id（mekanism:tin_ore）。 */
    public String id() {
        return this.id;
    }

    /** 供界面显示的方块 id（原版也返回完整 id，便于玩家核对）。 */
    public String blockId() {
        return this.blockIds.isEmpty() ? this.id : this.blockIds.get(0);
    }

    /** 命名空间（用于界面分组显示）。 */
    public String namespace() {
        return this.namespace;
    }

    /** 是否为自动识别到的模组矿物。 */
    public boolean modded() {
        return this.modded;
    }

    /** 默认是否启用（原版 true；识别到的模组矿物 false = 先不接管）。 */
    public boolean defaultEnabled() {
        return this.defaultEnabled;
    }

    /**
     * 显示名。原版用内置中文名；模组矿物取方块自身的名称（客户端会走模组的语言文件，
     * 服务端拿不到语言表时退回方块 id 的路径部分）。
     */
    public String displayName() {
        if (this.fixedName != null) {
            return this.fixedName;
        }
        Block block = primaryBlock();
        if (block != null) {
            try {
                String name = block.getName().getString();
                if (name != null && !name.isEmpty() && !looksLikeTranslationKey(name)) {
                    return name;
                }
            } catch (Throwable ignored) {
                // 语言表不可用时退回 id
            }
        }
        return prettify(this.blockIds.isEmpty() ? this.id : this.blockIds.get(0));
    }

    private static boolean looksLikeTranslationKey(String name) {
        return name.startsWith("block.") || name.startsWith("tile.") || name.indexOf('.') >= 0;
    }

    /**
     * 把方块 id 的路径部分美化成可读名字，例如 {@code oreprobe:tin_ore -> Tin Ore}。
     * <p>
     * 只在拿不到模组自己的翻译名时兜底（例如界面在主菜单阶段打开、语言表尚未就绪，
     * 或模组本身没提供对应语言文件），避免列表里出现 {@code tin_ore} 这种裸 id。
     */
    private static String prettify(String id) {
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        if (path.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean upperNext = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                sb.append(' ');
                upperNext = true;
            } else {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return sb.toString();
    }

    public OreGroup group() {
        return this.group;
    }

    public int defaultCount() {
        return this.defaultCount;
    }

    public int defaultSize() {
        return this.defaultSize;
    }

    public int defaultWeight() {
        return this.defaultWeight;
    }

    public int defaultMinY() {
        return this.defaultMinY;
    }

    public int defaultMaxY() {
        return this.defaultMaxY;
    }

    /** 该矿物会生成的方块（主力方块 + 已有的深板岩等变体）。 */
    public List<Block> blocks() {
        if (this.resolvedBlocks == null) {
            List<Block> list = new ArrayList<>(this.blockIds.size());
            for (String blockId : this.blockIds) {
                Block block = block(blockId);
                if (block != null) {
                    list.add(block);
                }
            }
            this.resolvedBlocks = List.copyOf(list);
        }
        return this.resolvedBlocks;
    }

    private Block primaryBlock() {
        List<Block> list = blocks();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 该矿物可以替换的方块状态 / 判定规则。 */
    public List<OreConfiguration.TargetBlockState> targets() {
        if (this.targets == null) {
            this.targets = buildTargets();
        }
        return this.targets;
    }

    /** 该矿物生成出来的方块（深层 / 普通一并包含），用于刷新区块时的清理。 */
    public Set<Block> oreBlocks() {
        if (this.oreBlocks == null) {
            Set<Block> set = new HashSet<>();
            for (OreConfiguration.TargetBlockState target : targets()) {
                set.add(target.state.getBlock());
            }
            this.oreBlocks = set;
        }
        return this.oreBlocks;
    }

    /**
     * 一行说明这种矿石会替换哪种岩石，例如 {@code stone only} / {@code stone + deepslate}。
     * <p>
     * 1.2.1 起写进识别日志：玩家问「为什么我的矿物没生成在深层」时，看一眼日志就知道
     * 是被判成了只长在浅层，还是压根没识别到。
     */
    public String hostSummary() {
        List<Block> blocks = blocks();
        if (blocks.isEmpty()) {
            return "unknown";
        }
        if (this.group == OreGroup.NETHER) {
            return "netherrack";
        }
        if (this.group == OreGroup.END) {
            return "end_stone";
        }
        if (blocks.size() > 1) {
            return "stone + deepslate";
        }
        Host host = hostOf(blocks.get(0));
        if (host == Host.DEEPSLATE) {
            return "deepslate only";
        }
        if (host == Host.BOTH) {
            return "stone + deepslate";
        }
        return "stone only";
    }

    private List<OreConfiguration.TargetBlockState> buildTargets() {
        List<OreConfiguration.TargetBlockState> list = new ArrayList<>(2);
        List<Block> blocks = blocks();
        if (blocks.isEmpty()) {
            return List.of();
        }
        switch (this.group) {
            case NETHER -> {
                // 原版下界石英 / 下界金只替换下界岩；远古残骸与识别到的模组矿物
                // 连黑石、玄武岩一起替换（模组的下界矿物常出现在玄武岩三角洲）
                boolean baseStone = this.modded || "ancient_debris".equals(this.id);
                RuleTest rule = baseStone ? OreTargets.BASE_STONE_NETHER : OreTargets.NETHERRACK;
                for (Block block : blocks) {
                    add(list, block, rule);
                }
            }
            case END -> {
                for (Block block : blocks) {
                    add(list, block, OreTargets.END_STONE);
                }
            }
            default -> buildOverworldTargets(list, blocks);
        }
        return List.copyOf(list);
    }

    // ============================================================================
    //  主世界：宿主岩石（浅层 / 深层）判定
    // ============================================================================

    /** Forge 约定的「矿物长在哪种岩石里」标签（路径在 {@code forge:ores_in_ground/} 下）。 */
    private static final String GROUND_STONE = "forge:ores_in_ground/stone";
    private static final String GROUND_DEEPSLATE = "forge:ores_in_ground/deepslate";

    /**
     * 方块名里的「深层」标记。
     * <p>
     * {@code deepslate} / {@code deep} 是英文惯例（原版 {@code deepslate_iron_ore}、
     * 部分模组 {@code iron_deepslate_ore} / {@code deep_ruby_ore}）；
     * {@code shenban} / {@code shenceng} 是拼音模组的写法（原石工艺 PGC 用
     * {@code shenban} 表示「深层」，如 {@code shenbanyanyuanshikuangshi} = 深层原石矿石）。
     * 方块 id 只允许 ASCII，所以这里不用考虑中文。
     */
    private static final Pattern DEEP_NAME = Pattern.compile("(^|_)deep(slate)?($|_)|shenban|shenceng");

    /** 单一形态矿物的宿主岩石判定结果。 */
    private enum Host { STONE, DEEPSLATE, BOTH }

    /**
     * 主世界矿物的替换目标：石头变体 → {@code #minecraft:stone_ore_replaceables}，
     * 深板岩变体 → {@code #minecraft:deepslate_ore_replaceables}。
     * <p>
     * 这两个标签**互不相交**（石头标签只有石头/花岗岩/闪长岩/安山岩，<b>不含深板岩</b>），
     * 因此只要保证「浅层方块只挂石头标签、深层方块只挂深板岩标签」，就绝不会出现
     * 「深层矿区生成浅层矿」。
     * <p>
     * <b>1.2.1 修的就是这里。</b>旧版对**只有一种方块形态**的模组矿物走了一条
     * 「浅层、深层都允许生成」的兜底分支，把同一个方块同时挂到两个标签上：模组只要没提供
     * 深板岩变体（或变体换了一套名字、配对不上），深板岩层里就会长出浅层矿石。
     * 实测排查两个整合包（133 个 jar、7 个含矿石的命名空间、25 条主世界矿石）后，
     * 有 11 条命中这条兜底分支——原石工艺的 8 种全部如此，因为它的深板岩变体叫
     * {@code shenban…kuangshi}，与主力方块名毫无字符串关系，配对必然失败。
     */
    private void buildOverworldTargets(List<OreConfiguration.TargetBlockState> list, List<Block> blocks) {
        if (blocks.size() == 1) {
            // 单一形态：先判定它到底该长在哪种岩石里，**绝不再两种都放**
            Block only = blocks.get(0);
            switch (hostOf(only)) {
                case DEEPSLATE -> add(list, only, OreTargets.DEEPSLATE);
                case BOTH -> {
                    add(list, only, OreTargets.STONE);
                    add(list, only, OreTargets.DEEPSLATE);
                }
                default -> add(list, only, OreTargets.STONE);
            }
            return;
        }
        // 两种以上：第 1 个是石头变体，其余里名字带「深层」标记的是深板岩变体
        add(list, blocks.get(0), OreTargets.STONE);
        Block deepslateBlock = null;
        for (int i = 1; i < blocks.size(); i++) {
            if (isDeepNamed(blocks.get(i))) {
                deepslateBlock = blocks.get(i);
                break;
            }
        }
        if (deepslateBlock == null) {
            // 名字里看不出来时沿用旧约定（归并出来的第 2 个就是变体）
            deepslateBlock = blocks.get(1);
        }
        add(list, deepslateBlock, OreTargets.DEEPSLATE);
    }

    /**
     * 单一形态的模组矿物该替换哪种岩石。
     * <p>
     * ① 优先信模组自己填的 {@code forge:ores_in_ground/*} 标签——这是 Forge 官方约定，
     * 写了标签就一定是作者的本意；<br>
     * ② 没有标签（实测绝大多数模组都不填：整包 122 个模组里只有 1 个填了）时看方块名，
     * 名字带「深层」标记的只放深层，其余**一律只放浅层**。
     * <p>
     * 「其余一律只放浅层」是保守但安全的选择：宁可这种矿石不生成在深板岩里，
     * 也不能让浅层矿石长在深层。真要两种岩石都生成，模组把方块同时填进
     * {@code ores_in_ground/stone} 与 {@code ores_in_ground/deepslate} 即可。
     */
    private static Host hostOf(Block block) {
        boolean stone = inGroundTag(block, GROUND_STONE);
        boolean deepslate = inGroundTag(block, GROUND_DEEPSLATE);
        if (stone || deepslate) {
            if (stone && deepslate) {
                return Host.BOTH;
            }
            return deepslate ? Host.DEEPSLATE : Host.STONE;
        }
        return isDeepNamed(block) ? Host.DEEPSLATE : Host.STONE;
    }

    /** 该方块的 id 是否带「深层」标记。 */
    private static boolean isDeepNamed(Block block) {
        if (block == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && isDeepName(id.getPath());
    }

    /**
     * 方块 id 的路径部分是否带「深层」标记。
     * <p>
     * 抽成纯字符串判定（不碰注册表），是为了能被 {@code /veinrebirth selftest} 直接跑表验证——
     * 这条规则一旦写错，症状就是"某种矿石长在错误的岩层"，很难靠肉眼发现。
     */
    static boolean isDeepName(String path) {
        return path != null && DEEP_NAME.matcher(path).find();
    }

    /**
     * 方块是否在指定方块标签里。参数是完整标签 id（如 {@code forge:ores_in_ground/deepslate}）。
     * <p>
     * 包级可见是为了让 {@code /veinrebirth selftest} 能直接验证这条路径——
     * 它是 {@link #hostOf} 的第一优先级，写错会静默退化成"看名字猜"。
     * <p>
     * <b>⚠️ 必须遍历 {@code getTagOrEmpty}，不要用 {@code BlockState.is(TagKey)}。</b>
     * 实测（Forge 1.20.1-47.3.22）：用 {@code BlockTags.create(...)} 现造的 TagKey 去问
     * {@code blockState.is(tag)} 永远返回 false；原版那些能返回 true，是因为用的是
     * {@code BlockTags} 里的静态实例（比较似乎走了实例身份）。同一个标签用
     * {@code getTagOrEmpty} 遍历却完全正常。
     * <p>
     * 这个坑很隐蔽：写错了不报错、不崩溃，只是判定悄悄退化成"看名字猜"，
     * 症状要到世界里才会显形。是 {@code /veinrebirth selftest} 把它抓出来的。
     */
    static boolean inGroundTag(Block block, String tagId) {
        if (block == null || tagId == null) {
            return false;
        }
        try {
            TagKey<Block> key = BlockTags.create(new ResourceLocation(tagId));
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                if (holder != null && holder.isBound() && holder.value() == block) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 标签不可用（例如数据包未加载）时静默跳过
        }
        return false;
    }

    private static void add(List<OreConfiguration.TargetBlockState> list, Block block, RuleTest rule) {
        BlockState state = block.defaultBlockState();
        list.add(OreConfiguration.target(rule, state));
    }

    private static Block block(String id) {
        try {
            // BuiltInRegistries.BLOCK 是 DefaultedRegistry，取不到时返回默认值（空气）而不是 null
            Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(id));
            return block == Blocks.AIR ? null : block;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 集合运算

    /** 原版矿物会生成的方块（用于清理时判断）。 */
    public static Set<Block> allOreBlocks() {
        Set<Block> set = new HashSet<>();
        for (OreType type : ORDERED) {
            if (!type.modded) {
                set.addAll(type.oreBlocks());
            }
        }
        return set;
    }

    /** 当前已被本模组接管的模组矿物方块（即这些矿物 enabled = true）。 */
    public static Set<Block> takeoverBlocks() {
        Set<Block> set = new HashSet<>();
        for (OreType type : ORDERED) {
            if (type.modded && ConfigManager.get(type).isEnabled()) {
                set.addAll(type.oreBlocks());
            }
        }
        return set;
    }

    /** 指定分组内已被接管的模组矿物方块。 */
    public static Set<Block> takeoverBlocks(OreGroup group) {
        Set<Block> set = new HashSet<>();
        for (OreType type : ORDERED) {
            if (type.modded && type.group == group && ConfigManager.get(type).isEnabled()) {
                set.addAll(type.oreBlocks());
            }
        }
        return set;
    }

    /** 本模组完全说了算的方块集合 = 原版 11 种 + 已接管的模组矿物。 */
    public static Set<Block> handledBlocks() {
        Set<Block> set = new HashSet<>(allOreBlocks());
        set.addAll(takeoverBlocks());
        return set;
    }

    /**
     * 本模组"曾经接管过、因此可能在世界里留下过方块"的模组矿物方块。
     * <p>
     * 补的是 {@link #handledBlocks()} 的一个漏洞：某种矿物被接管过、之后又关掉接管时，
     * 它先前生成的方块还留在世界里，却因为不再是"已接管"而脱离了清理范围，再也清不掉。
     * 这里按 {@link ConfigManager#everHandled()} 的历史来兜住这部分。
     */
    public static Set<Block> everHandledBlocks() {
        Set<String> handled = ConfigManager.everHandled();
        if (handled.isEmpty()) {
            return Set.of();
        }
        Set<Block> set = new HashSet<>();
        for (OreType type : ORDERED) {
            if (type.modded && handled.contains(type.id)) {
                set.addAll(type.oreBlocks());
            }
        }
        return set;
    }

    /**
     * 所有已识别模组矿物的方块（不论当前是否接管）。
     * <p>
     * 只有玩家显式执行 {@code /veinrebirth refresh <半径> clean all} 时才会用到：它比
     * {@link #everHandledBlocks()} 更狠，会连"本模组从未接管、由别的模组自己生成"的矿石
     * 一起清掉，且清掉后本模组不会重建——所以不能作为默认行为。
     */
    public static Set<Block> allModdedBlocks() {
        Set<Block> set = new HashSet<>();
        for (OreType type : ORDERED) {
            if (type.modded) {
                set.addAll(type.oreBlocks());
            }
        }
        return set;
    }

    /** 该方块是否已经被某个已登记的矿物条目占用（原版内置或已识别）。 */
    public static boolean ownsBlock(ResourceLocation blockId) {
        String key = blockId.toString();
        for (OreType type : ORDERED) {
            if (type.blockIds.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** 识别到的模组矿物数量。 */
    public static int moddedCount() {
        int count = 0;
        for (OreType type : ORDERED) {
            if (type.modded) {
                count++;
            }
        }
        return count;
    }

    /** 按 id 查找（原版短名或模组矿物的完整 id），找不到返回 null。 */
    public static OreType byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        OreType direct = BY_ID.get(key);
        if (direct != null) {
            return direct;
        }
        // 容错：允许省略 minecraft: 前缀，或用方块 id 直接查
        for (OreType type : ORDERED) {
            if (type.id.equalsIgnoreCase(key) || type.blockId().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}
