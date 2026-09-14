package com.lingyao.veinrebirth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 其它模组矿物的自动识别。
 * <p>
 * 现实中的模组（通用机械、热力系列、工业时代…）各自注册自己的矿石方块，命名基本都遵循
 * {@code <material>_ore}（普通矿石）与 {@code deepslate_<material>_ore} / {@code <material>_deepslate_ore}
 * （深板岩变体）两种形态。本类据此扫描方块注册表，把识别到的矿石登记进 {@link OreType}，
 * 使它们和原版矿物一样可以在配置界面里用滑块调整、也能写进配置文件。
 * <p>
 * 判定顺序（越靠前越可信）：
 * <ol>
 *     <li>{@code forge:ores} 方块标签——Forge 官方约定，模组只要正确填了标签就一定会被识别到；</li>
 *     <li>{@code forge:ores_in_ground/{stone,deepslate,netherrack,end_stone}} 标签——用来判定矿物属于哪个维度；</li>
 *     <li>方块 id 命名规则——没有打标签的模组靠这个兜底；</li>
 *     <li>配置文件里手写的 {@code dimension}——玩家的判断永远优先于上面任何一条。</li>
 * </ol>
 * 标签要在数据包加载完成后才有内容，所以识别分两次跑：
 * {@code FMLCommonSetupEvent} 先按名称扫一遍（保证世界生成前就有结果），
 * {@code ServerStartedEvent} 再用标签补漏并修正维度归属。
 * <p>
 * 需要强调的是：<b>第 2、3 步都可能判错</b>。模组作者未必会填 {@code ores_in_ground} 标签，
 * 而材料名也未必带上 nether / end 这样的词——匠魂的钴矿就是典型（既没标签、名字里也没有
 * nether），早期版本会把它当成主世界矿物，接管后生成到主世界去。所以每种模组矿物的配置段里
 * 都带一行可手写的 {@code dimension}，用来兜住这类判定失败的情况。
 * <p>
 * 识别到的矿物<b>默认不接管</b>（{@code enabled = false}）：本模组完全不碰它，保持其它模组自己的生成。
 * 玩家在配置界面把它打开后才会接管——先清除其它模组生成的这种矿物，再按本模组的数值重新生成，
 * 这样"总产量"才真正由滑块说了算。
 */
public final class OreDiscoverer {

    /** 主方块所在的维度判定标签（Forge 约定，路径在 forge:ores_in_ground/ 下）。 */
    private static final String GROUND_STONE = "stone";
    private static final String GROUND_DEEPSLATE = "deepslate";
    private static final String GROUND_NETHERRACK = "netherrack";
    private static final String GROUND_END_STONE = "end_stone";

    /** 关键词 -> 默认高度区间（保底值，玩家可在界面或配置文件里改）。 */
    private static final String[] SHALLOW = {"coal", "lignite", "peat", "salt"};
    private static final String[] MID = {"iron", "copper", "tin", "zinc", "aluminium", "aluminum", "lead",
            "silver", "nickel", "osmium", "manganese", "chromium", "cobalt", "titanium", "tungsten",
            "bauxite", "cassiterite", "beryllium", "magnetite", "hematite", "galena"};
    private static final String[] DEEP = {"gold", "redstone", "diamond", "lapis", "emerald", "ruby",
            "sapphire", "amethyst", "uranium", "plutonium", "platinum", "iridium", "draconium",
            "cinnabar", "sulfur", "fluorite", "apatite", "niter"};

    /**
     * 「材料名本身就锁定下界」的关键词，是判定维度的最后一道保险。
     * <p>
     * {@code cobalt} 必须在这里：匠魂的钴矿既没有打 {@code forge:ores_in_ground/netherrack}
     * 标签，名字里也不含 nether，只靠标签和命名两套规则都认不出来，会被当成主世界矿物而
     * 生成到主世界去。同理还有 blaze / glowstone 这类只会出现在下界的材料。
     */
    private static final String[] NETHER_MATERIALS = {
            "nether", "quartz", "ancient", "soul", "blackstone", "basalt", "magma",
            "blaze", "glowstone", "cobalt", "netherite", "wither", "brimstone", "cinder",
    };

    /**
     * 「材料名本身就锁定末地」的关键词。
     * <p>
     * {@code draconium} 对应龙之研究的末地矿，它和匠魂的钴矿是同一类问题。
     */
    private static final String[] END_MATERIALS = {
            "draconium", "chorus", "purpur", "shulker", "void", "awakened", "elytra",
    };

    private static boolean initialDone;
    private static boolean tagPhaseDone;
    private static int lastAdded;

    private OreDiscoverer() {
    }

    /** 第一次识别（FMLCommonSetupEvent）：方块都已注册，但方块标签还没加载。 */
    public static synchronized int discoverInitial() {
        if (!initialDone) {
            initialDone = true;
            lastAdded = discover(false);
        }
        return lastAdded;
    }

    /** 第二次识别（TagsUpdatedEvent）：标签已就绪，补漏 + 修正维度。 */
    public static synchronized int discoverWithTags() {
        lastAdded = discover(true);
        tagPhaseDone = true;
        return lastAdded;
    }

    /** 手动重新扫描（{@code /veinrebirth scan} 用）：忽略"只跑一次"的限制，标签可读时一并使用。 */
    public static synchronized int rescan() {
        initialDone = true;
        lastAdded = discover(true);
        tagPhaseDone = true;
        return lastAdded;
    }

    public static boolean isTagPhaseDone() {
        return tagPhaseDone;
    }

    // ------------------------------------------------------------------ 主流程

    private static int discover(boolean includeTags) {
        int before = OreType.moddedCount();
        if (!ConfigManager.isDetectModdedOres()) {
            return 0;
        }
        try {
            runDiscovery(includeTags);
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Failed to scan modded ores", t);
        }
        int added = OreType.moddedCount() - before;
        if (added > 0) {
            // 首次 load() 时这些矿物还没登记，它们的配置段被当成"无法识别"原样留存；
            // 这里再解析一次，把之前保存过的数值正确读回来，避免退化成默认值。
            ConfigManager.reloadAfterDiscovery();
            ConfigManager.ensureSettingsForAll();
            ConfigManager.save();
            VeinRebirthMod.LOGGER.info("[VeinRebirth] Discovered {} modded ore(s), total {}",
                    added, OreType.moddedCount());
        }
        if (added > 0 || includeTags) {
            logHosts();
        }
        return added;
    }

    /**
     * 逐条打印每种模组矿物「会替换哪种岩石」。
     * <p>
     * 排查「某种矿物生成在了错误的岩层」时，这一行最省事：
     * {@code stone only} = 深板岩层里不会有它（模组没提供深层变体时的正确行为）；
     * {@code deepslate only} = 只长在深层；{@code stone + deepslate} = 两层都有。
     * <p>
     * 1.2.1 修的「深层矿区生成浅层矿」就是判定错误导致的，留这行日志方便下次自查。
     */
    private static void logHosts() {
        for (OreType type : OreType.all()) {
            if (type.modded()) {
                VeinRebirthMod.LOGGER.info("[VeinRebirth]   ore {} -> {}",
                        type.id(), type.hostSummary());
            }
        }
    }

    private static void runDiscovery(boolean includeTags) {
        // ---------------- 1. 收集候选方块 ----------------
        Set<Block> candidates = new LinkedHashSet<>();
        if (includeTags) {
            candidates.addAll(tagBlocks("forge", "ores"));
        }
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id != null && looksLikeOre(id)) {
                candidates.add(block);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 维度判定标签（标签阶段才可能有内容）
        Set<Block> groundNetherrack = includeTags ? tagBlocks("forge", "ores_in_ground/" + GROUND_NETHERRACK) : Set.of();
        Set<Block> groundEnd = includeTags ? tagBlocks("forge", "ores_in_ground/" + GROUND_END_STONE) : Set.of();

        // ---------------- 2. id -> 方块 ----------------
        Map<ResourceLocation, Block> byId = new LinkedHashMap<>();
        for (Block block : candidates) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id != null && !OreType.ownsBlock(id)) {
                byId.put(id, block);
            }
        }

        // ---------------- 3. 深板岩变体归并到主力方块 ----------------
        Map<ResourceLocation, List<ResourceLocation>> merged = new LinkedHashMap<>();
        for (ResourceLocation id : byId.keySet()) {
            ResourceLocation base = deepslateBase(id);
            if (base != null && byId.containsKey(base)) {
                merged.computeIfAbsent(base, k -> new ArrayList<>()).add(id);
            } else {
                merged.computeIfAbsent(id, k -> new ArrayList<>());
            }
        }

        // ---------------- 4. 登记 ----------------
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> entry : merged.entrySet()) {
            ResourceLocation primaryId = entry.getKey();
            List<ResourceLocation> variants = entry.getValue();

            List<String> blockIds = new ArrayList<>(variants.size() + 1);
            blockIds.add(primaryId.toString());
            for (ResourceLocation variant : variants) {
                blockIds.add(variant.toString());
            }

            OreGroup group = classify(primaryId, byId.get(primaryId), variants, byId, groundNetherrack, groundEnd);
            if (group.vanillaOnly()) {
                // 山地分组只服务原版绿宝石，模组矿物一律不放进来
                group = OreGroup.OVERWORLD;
            }
            int[] range = defaultHeight(primaryId.getPath(), group);

            OreType.registerModded(primaryId.toString(), group, blockIds,
                    OreType.MODDED_DEFAULT_COUNT, OreType.MODDED_DEFAULT_SIZE,
                    OreType.MODDED_DEFAULT_WEIGHT, range[0], range[1]);
        }
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 命名规则：以 {@code _ore} 结尾即视为候选。
     * <p>
     * <b>深板岩变体也要收进来</b>——它们不是独立矿物，但必须出现在候选表里，
     * 后面的归并步骤才能把 {@code deepslate_tin_ore} 挂到 {@code tin_ore} 上，
     * 让"浅层出普通矿石、深层出深板岩矿石"这条规则成立。
     * 早期版本在这里就把变体滤掉了，结果是深层也刷普通矿石，已修正。
     */
    private static boolean looksLikeOre(ResourceLocation id) {
        if (VeinRebirthMod.MOD_ID.equals(id.getNamespace())) {
            return false;
        }
        return id.getPath().endsWith("_ore");
    }

    /**
     * 深板岩变体 -> 主力方块 id。
     * <p>
     * 兼容两种命名：{@code deepslate_tin_ore}（前缀式，原版风格）与
     * {@code tin_deepslate_ore}（后缀式，部分模组使用）。不是变体时返回 null。
     */
    private static ResourceLocation deepslateBase(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith("deepslate_") && path.endsWith("_ore")) {
            return new ResourceLocation(id.getNamespace(), path.substring("deepslate_".length()));
        }
        if (path.endsWith("_deepslate_ore")) {
            String base = path.substring(0, path.length() - "_deepslate_ore".length());
            if (!base.isEmpty()) {
                return new ResourceLocation(id.getNamespace(), base + "_ore");
            }
        }
        return null;
    }

    /** 判断矿物属于哪个维度：先看 Forge 的 ores_in_ground 标签，再看名称关键词。 */
    private static OreGroup classify(ResourceLocation id, Block primary, List<ResourceLocation> variants,
            Map<ResourceLocation, Block> byId, Set<Block> groundNetherrack, Set<Block> groundEnd) {
        Set<Block> all = new LinkedHashSet<>();
        if (primary != null) {
            all.add(primary);
        }
        for (ResourceLocation variant : variants) {
            Block block = byId.get(variant);
            if (block != null) {
                all.add(block);
            }
        }
        if (!all.isEmpty()) {
            if (containsAny(all, groundNetherrack)) {
                return OreGroup.NETHER;
            }
            if (containsAny(all, groundEnd)) {
                return OreGroup.END;
            }
        }

        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (containsAnyKeyword(path, NETHER_MATERIALS)) {
            return OreGroup.NETHER;
        }
        // "end" 单独作前缀判定：直接 contains 会把 weekend、legend 这类词也吃进来
        if (path.startsWith("end") || containsKeyword(path, "end_") || containsKeyword(path, "ender")
                || containsAnyKeyword(path, END_MATERIALS)) {
            return OreGroup.END;
        }
        return OreGroup.OVERWORLD;
    }

    private static boolean containsAnyKeyword(String path, String[] keywords) {
        for (String keyword : keywords) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Set<Block> blocks, Set<Block> tagSet) {
        if (tagSet.isEmpty()) {
            return false;
        }
        for (Block block : blocks) {
            if (tagSet.contains(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsKeyword(String path, String keyword) {
        return path.contains(keyword);
    }

    /** 按矿物名称关键词给一个合理的默认高度区间。 */
    private static int[] defaultHeight(String path, OreGroup group) {
        switch (group) {
            case NETHER -> {
                return new int[]{10, 117};
            }
            case END -> {
                return new int[]{0, 80};
            }
            default -> {
                String lower = path.toLowerCase(Locale.ROOT);
                for (String keyword : SHALLOW) {
                    if (lower.contains(keyword)) {
                        return new int[]{0, 192};
                    }
                }
                for (String keyword : MID) {
                    if (lower.contains(keyword)) {
                        return new int[]{-64, 72};
                    }
                }
                for (String keyword : DEEP) {
                    if (lower.contains(keyword)) {
                        return new int[]{-64, 32};
                    }
                }
                return new int[]{-64, 64};
            }
        }
    }

    // ------------------------------------------------------------------ 标签读取

    /** 读取一个方块标签的内容；标签未加载或不存在时返回空集合。 */
    private static Set<Block> tagBlocks(String namespace, String path) {
        Set<Block> set = new LinkedHashSet<>();
        try {
            TagKey<Block> key = BlockTags.create(new ResourceLocation(namespace, path));
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                if (holder != null && holder.isBound()) {
                    set.add(holder.value());
                }
            }
        } catch (Throwable ignored) {
            // 标签不可用（例如客户端还没同步）时静默跳过
        }
        return set;
    }
}
