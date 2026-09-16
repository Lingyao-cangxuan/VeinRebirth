package com.lingyao.veinrebirth;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * /veinrebirth 命令（同时保留旧名 /orecfg 与 /oregenconfig，兼容 1.1.x）。
 * <p>
 * <pre>
 * /veinrebirth refresh &lt;半径&gt; [clean]  刷新周围区块的矿物
 * /veinrebirth list [关键字]            列出所有已登记的矿物（含自动识别到的模组矿物）
 * /veinrebirth scan                     重新扫描识别其它模组的矿物
 * /veinrebirth reload                   重新读取配置文件
 * /veinrebirth save                     把当前数值写回配置文件
 * /veinrebirth veins [true|false]       查看 / 切换原版大型矿脉
 * /veinrebirth info &lt;矿物&gt;             查看某种矿物的当前数值
 * /veinrebirth set &lt;矿物&gt; &lt;字段&gt; &lt;值&gt;   直接修改某个字段
 * /veinrebirth preset [名称]            列出预设方案 / 套用某个预设
 * /veinrebirth share                    生成分享码（按 T 打开聊天栏后点击复制，同时写入文件）
 * /veinrebirth share import &lt;码|file&gt;   从分享码或文件导入整套数值
 * /veinrebirth defaults                 全部恢复默认值
 * /veinrebirth selftest                 自检：岩层判定表 + 「浅层方块绝不挂深板岩规则」不变量
 * </pre>
 */
public final class OreGenCommand {

    private static final List<String> KEYS = List.of("enabled", "count", "size", "weight", "min_y", "max_y");

    /** {@code /veinrebirth list} 一次最多列出的条数，避免上百种矿物把聊天栏刷爆。 */
    private static final int LIST_LIMIT = 30;

    /**
     * 原版聊天框单条消息上限 256 字符，超过这个长度的分享码没法通过聊天栏传（会被截断），
     * 所以长码只在聊天里显示开头一段（点击复制的仍是完整码），并提示改用文件 / 界面。
     * <p>
     * OGC2 紧凑格式下纯原版配置约 124 字符，通常在 250 以内——也就是说现在多数情况
     * 都能在聊天栏里完整显示、直接整段复制；只有接管了很多模组矿物（id 较长）时才走预览分支。
     */
    private static final int CHAT_SAFE_LENGTH = 250;

    /** 长码在聊天里预览的字符数。 */
    private static final int PREVIEW_LENGTH = 120;

    private OreGenCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(build("veinrebirth"));   // 主命令
        event.getDispatcher().register(build("orecfg"));        // 兼容 1.1.x 的旧短名
        event.getDispatcher().register(build("oregenconfig"));  // 兼容 1.1.x 的旧长名
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("refresh")
                        .executes(ctx -> refresh(ctx, 2, ChunkRefresher.CleanMode.NONE))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, ChunkRefresher.MAX_RADIUS))
                                .executes(ctx -> refresh(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                        ChunkRefresher.CleanMode.NONE))
                                .then(Commands.literal("clean")
                                        .executes(ctx -> refresh(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                                ChunkRefresher.CleanMode.MANAGED))
                                        .then(Commands.literal("all")
                                                .executes(ctx -> refresh(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        ChunkRefresher.CleanMode.ALL))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, ""))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "filter")))))
                .then(Commands.literal("scan").executes(OreGenCommand::scan))
                .then(Commands.literal("reload").executes(OreGenCommand::reload))
                .then(Commands.literal("save").executes(OreGenCommand::save))
                .then(Commands.literal("defaults").executes(OreGenCommand::defaults))
                .then(Commands.literal("selftest").executes(OreGenCommand::selftest))
                .then(Commands.literal("veins")
                        .executes(OreGenCommand::veinsInfo)
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests(OreGenCommand::suggestBooleans)
                                .executes(OreGenCommand::veinsSet)))
                .then(Commands.literal("info")
                        .then(Commands.argument("ore", StringArgumentType.word())
                                .suggests(OreGenCommand::suggestOres)
                                .executes(OreGenCommand::info)))
                .then(Commands.literal("set")
                        .then(Commands.argument("ore", StringArgumentType.word())
                                .suggests(OreGenCommand::suggestOres)
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KEYS, builder))
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(OreGenCommand::set)))))
                .then(Commands.literal("preset")
                        .executes(OreGenCommand::presetList)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(OreGenCommand::suggestPresets)
                                .executes(OreGenCommand::presetApply)))
                .then(Commands.literal("share")
                        .executes(OreGenCommand::shareExport)
                        .then(Commands.literal("import")
                                .then(Commands.argument("code", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("file"), builder))
                                        .executes(OreGenCommand::shareImport))));
    }

    private static CompletableFuture<Suggestions> suggestOres(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Arrays.stream(OreType.values()).map(OreType::id).toList(), builder);
    }

    private static CompletableFuture<Suggestions> suggestBooleans(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("true", "false"), builder);
    }

    // ============================================================================
    //  自检 —— 「矿物是否挂在正确的岩层上」的回归守卫（1.2.1 新增）
    // ============================================================================

    /**
     * 检查两件事：
     * <ol>
     *     <li>方块名里的「深层」标记判定表（含原石工艺的拼音命名）；</li>
     *     <li>结构不变量：浅层方块绝不能挂到 deepslate_ore_replaceables 规则上。</li>
     * </ol>
     */
    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int pass = 0;
        int fail = 0;

        // ① 深层命名判定表：{方块路径, 期望}
        String[][] cases = {
                {"deepslate_iron_ore", "true"},
                {"silver_deepslate_ore", "true"},
                {"deep_ruby_ore", "true"},
                {"shenbanyanyuanshikuangshi", "true"},
                {"shenbanjianlaohuangyukuangshi", "true"},
                {"yuanshikuangshi", "false"},
                {"xinyuekuangshi", "false"},
                {"manyuejinshukuangshi", "false"},
                {"aixubingyukuangshi", "false"},
                {"tin_ore", "false"},
                {"copper_ore", "false"},
                {"ancient_debris", "false"},
                {"legend_ore", "false"},
                {"core", "false"},
        };
        for (String[] c : cases) {
            boolean got = OreType.isDeepName(c[0]);
            boolean want = Boolean.parseBoolean(c[1]);
            if (got == want) {
                pass++;
            } else {
                fail++;
                VeinRebirthMod.LOGGER.error("[VeinRebirth] SELFTEST FAIL isDeepName({}) = {}, want {}",
                        c[0], got, want);
            }
        }

        // ② 结构不变量
        for (OreType type : OreType.values()) {
            if (type.group() == OreGroup.NETHER || type.group() == OreGroup.END) {
                continue;
            }
            int stone = 0;
            int deep = 0;
            for (OreConfiguration.TargetBlockState target : type.targets()) {
                if (target.target == OreTargets.STONE) {
                    stone++;
                } else if (target.target == OreTargets.DEEPSLATE) {
                    deep++;
                }
            }
            boolean ok = type.blocks().size() > 1 ? (stone == 1 && deep == 1) : (stone + deep) == 1;
            if (ok) {
                pass++;
            } else {
                fail++;
                VeinRebirthMod.LOGGER.error(
                        "[VeinRebirth] SELFTEST FAIL {} : stone={} deepslate={} blocks={}",
                        type.id(), stone, deep, type.blocks().size());
            }
        }

        // ③ 标签读取这条路径 —— 它是 hostOf 的第一优先级。
        //    用本模组自带的 veinrebirth:selftest_probe 验证"机制本身"，不依赖任何第三方模组：
        //    ⚠️ 切不可用 BlockState.is(新建 TagKey)，那条路在 1.20.1 上恒为 false，见 OreType#inGroundTag。
        boolean[] tagCases = {
                checkGroundTag("minecraft:stone", "veinrebirth:selftest_probe", true),
                checkGroundTag("minecraft:granite", "veinrebirth:selftest_probe", true),
                checkGroundTag("minecraft:dirt", "veinrebirth:selftest_probe", false),
                checkGroundTag("minecraft:deepslate_coal_ore",
                        "forge:ores_in_ground/deepslate", true),
                checkGroundTag("minecraft:coal_ore", "forge:ores_in_ground/deepslate", false),
        };
        for (boolean ok : tagCases) {
            if (ok) {
                pass++;
            } else {
                fail++;
            }
        }

        int total = pass + fail;
        int passed = pass;
        int failed = fail;
        VeinRebirthMod.LOGGER.info("[VeinRebirth] SELFTEST total={} pass={} fail={}", total, passed, failed);
        if (failed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§a[矿脉重生] §f自检通过：§a" + passed + "§f/" + total + " 项"), false);
        } else {
            source.sendFailure(Component.literal(
                    "§c[矿脉重生] 自检失败 §c" + failed + "§f/" + total + " 项，详见日志"));
        }
        return failed == 0 ? 1 : 0;
    }

    /** 断言某个方块是否在指定方块标签里（标签 id 写全，如 {@code forge:ores_in_ground/deepslate}）。 */
    private static boolean checkGroundTag(String blockId, String tagId, boolean want) {
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
        boolean got = block != Blocks.AIR && OreType.inGroundTag(block, tagId);
        if (got != want) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] SELFTEST FAIL tag {} in {} = {}, want {}",
                    blockId, tagId, got, want);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ refresh

    private static int refresh(CommandContext<CommandSourceStack> ctx, int radius, ChunkRefresher.CleanMode mode) {
        CommandSourceStack source = ctx.getSource();
        // 服务端控制台不属于任何维度，getLevel() 会是 null，这里兜底到主世界
        ServerLevel level = source.getLevel();
        if (level == null && source.getServer() != null) {
            level = source.getServer().overworld();
        }
        if (level == null) {
            source.sendFailure(Component.literal("§c[矿脉重生] 无法确定要刷新的维度。"));
            return 0;
        }
        final ServerLevel targetLevel = level;
        BlockPos center = BlockPos.containing(source.getPosition());

        String modeText = switch (mode) {
            case NONE -> "";
            case MANAGED -> "（先清除本模组生成过的矿物）";
            case ALL -> "（先清除范围内所有矿石方块，含未接管的模组矿石）";
        };
        source.sendSuccess(() -> Component.literal("§e[矿脉重生] §f正在刷新 §e" + targetLevel.dimension().location()
                + " §f中半径 " + radius + " 区块内的矿物" + modeText + "……"), false);

        ChunkRefresher.Result result;
        try {
            result = ChunkRefresher.refresh(level, center, radius, mode);
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Refresh failed", t);
            String message = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage());
            source.sendFailure(Component.literal("§c[矿脉重生] 刷新失败：" + message));
            return 0;
        }

        ChunkRefresher.Result finalResult = result;
        source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f完成：共扫描 §e" + finalResult.chunksScanned()
                + " §f个区块，其中 §e" + finalResult.chunksTouched() + " §f个区块生成了新矿物。"), true);
        if (finalResult.oreBlocksRemoved() > 0) {
            source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f清除了 §e"
                    + finalResult.oreBlocksRemoved() + " §f个已生成的矿石方块。"), false);
        }
        if (finalResult.veinBlocksRemoved() > 0) {
            source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f另清除了 §e"
                    + finalResult.veinBlocksRemoved() + " §f个原版大型矿脉方块。"), false);
        }
        if (mode == ChunkRefresher.CleanMode.ALL) {
            source.sendSuccess(() -> Component.literal(
                    "§7提示：clean all 连未被本模组接管的模组矿石也清掉了，这些方块不会被本模组重建。"
                            + "只想清本模组留下的残留，请用 §e/veinrebirth refresh <半径> clean"), false);
        }
        source.sendSuccess(() -> Component.literal("§7提示：本命令只对已生成的区块补加 / 重建矿物，不会重新生成地形。"), false);
        return finalResult.chunksTouched();
    }

    // ------------------------------------------------------------------ list / scan

    private static int list(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSourceStack source = ctx.getSource();
        String keyword = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        OreType[] all = OreType.values();
        int matched = 0;
        source.sendSuccess(() -> Component.literal("§6===== §e矿物清单 §7(共 " + all.length + " 种，其中自动识别 "
                + OreType.moddedCount() + " 种) §6====="), false);

        for (OreType type : all) {
            if (!keyword.isEmpty()
                    && !type.id().toLowerCase(Locale.ROOT).contains(keyword)
                    && !type.displayName().toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            matched++;
            if (matched > LIST_LIMIT) {
                continue;
            }
            OreSettings settings = ConfigManager.get(type);
            String mark = type.modded() ? (settings.isEnabled() ? "§b[接管]§r " : "§8[未接管]§r ") : "";
            String line = "§f" + mark + type.displayName() + " §7(" + type.id() + ") §8"
                    + type.group().displayName()
                    + (settings.willGenerate() ? "" : " §8·未生成");
            source.sendSuccess(() -> Component.literal(line), false);
        }

        if (matched == 0) {
            source.sendSuccess(() -> Component.literal("§7没有匹配「" + keyword + "」的矿物。"), false);
            return 0;
        }
        if (matched > LIST_LIMIT) {
            int rest = matched - LIST_LIMIT;
            source.sendSuccess(() -> Component.literal("§7……还有 " + rest + " 种未显示，可用 §e/veinrebirth list <关键字>§7 过滤，"
                    + "或在游戏内配置界面用搜索框浏览。"), false);
        }
        return matched;
    }

    private static int scan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§e[矿脉重生] §f正在扫描方块注册表与标签，识别其它模组的矿物……"), false);
        int added;
        try {
            added = OreDiscoverer.rescan();
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Rescan failed", t);
            String message = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage());
            source.sendFailure(Component.literal("§c[矿脉重生] 扫描失败：" + message));
            return 0;
        }
        int total = OreType.moddedCount();
        if (added > 0) {
            source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f新识别到 §e" + added
                    + " §f种模组矿物，累计 §e" + total + " §f种。已写入配置文件，默认不接管。"), true);
            source.sendSuccess(() -> Component.literal(
                    "§7提示：在配置界面里把要接管的矿物打开，或执行 §e/veinrebirth list §7查看清单。"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f没有新发现，当前累计识别到 §e" + total + " §f种模组矿物。"), false);
        }
        if (!ConfigManager.isDetectModdedOres()) {
            source.sendSuccess(() -> Component.literal(
                    "§7注意：配置文件里 detect_modded_ores = false，识别功能已关闭，本次扫描未生效。"), false);
        }
        return added;
    }

    // ------------------------------------------------------------------ reload / save / defaults

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.load();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[矿脉重生] §f已重新读取配置文件：§7" + ConfigManager.file()), true);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[矿脉重生] §f已保存到：§7" + ConfigManager.file()), true);
        return 1;
    }

    private static int defaults(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.resetToDefaults();
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[矿脉重生] §f所有矿物已恢复原版默认数值并保存。"), true);
        return 1;
    }

    // ------------------------------------------------------------------ veins

    private static int veinsInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean on = ConfigManager.isVeinOresEnabled();
        source.sendSuccess(() -> Component.literal("§6===== §e原版大型矿脉 §6====="), false);
        source.sendSuccess(() -> Component.literal("§f    状态 = " + (on ? "§a已启用（保留原版矿脉）" : "§c已禁用（清除矿脉）")), false);
        source.sendSuccess(() -> Component.literal("§7    说明：1.18+ 的铜矿脉 / 铁矿脉，由地形噪声直接生成，"), false);
        source.sendSuccess(() -> Component.literal("§7          不经过数据包，所以无法用 biome_modifier 移除。"), false);
        source.sendSuccess(() -> Component.literal("§7          生成高度：Y " + OreVeins.MIN_Y + " ~ " + OreVeins.MAX_Y), false);
        source.sendSuccess(() -> Component.literal("§f    切换：§e/veinrebirth veins <true|false>"), false);
        return on ? 1 : 0;
    }

    private static int veinsSet(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "value");
        boolean value;
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "on".equalsIgnoreCase(raw)) {
            value = true;
        } else if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "off".equalsIgnoreCase(raw)) {
            value = false;
        } else {
            ctx.getSource().sendFailure(Component.literal("无法识别的取值：" + raw + "，请用 true 或 false"));
            return 0;
        }
        ConfigManager.setVeinOresEnabled(value);
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[矿脉重生] §f原版大型矿脉已"
                + (value ? "§a启用" : "§c禁用") + "§f，并已保存。"), true);
        if (!value) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7提示：已生成过的区块请执行 §e/veinrebirth refresh 4§7 清除其中的矿脉方块。"), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ info

    private static int info(CommandContext<CommandSourceStack> ctx) {
        OreType type = OreType.byId(StringArgumentType.getString(ctx, "ore"));
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal("未知矿物 id：" + StringArgumentType.getString(ctx, "ore")));
            return 0;
        }
        OreSettings settings = ConfigManager.get(type);
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6===== §e" + type.displayName() + " §7(" + type.id() + ") §6====="), false);
        source.sendSuccess(() -> Component.literal("§f    启用(enabled) = §e" + settings.isEnabled()), false);
        source.sendSuccess(() -> Component.literal("§f    数量(count)   = §e" + settings.getCount() + " §7条/区块"), false);
        source.sendSuccess(() -> Component.literal("§f    规模(size)    = §e" + settings.getSize() + " §7方块/条"), false);
        source.sendSuccess(() -> Component.literal("§f    权重(weight)  = §e" + settings.getWeight() + "%"), false);
        source.sendSuccess(() -> Component.literal("§f    高度(min~max) = §e" + settings.getMinY() + " ~ " + settings.getMaxY()), false);
        return 1;
    }

    // ------------------------------------------------------------------ set

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String oreId = StringArgumentType.getString(ctx, "ore");
        OreType type = OreType.byId(oreId);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal("未知矿物 id：" + oreId));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
        String value = StringArgumentType.getString(ctx, "value");
        if (!KEYS.contains(key)) {
            ctx.getSource().sendFailure(Component.literal("未知字段：" + key + "，可用字段：" + String.join(", ", KEYS)));
            return 0;
        }

        OreSettings settings = ConfigManager.get(type);
        try {
            switch (key) {
                case "enabled" -> settings.setEnabled(parseBoolean(value));
                case "count" -> settings.setCount(Integer.parseInt(value));
                case "size" -> settings.setSize(Integer.parseInt(value));
                case "weight" -> settings.setWeight(Integer.parseInt(value));
                case "min_y" -> settings.setMinY(Integer.parseInt(value));
                case "max_y" -> settings.setMaxY(Integer.parseInt(value));
                default -> {
                    return 0;
                }
            }
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("无法识别的数值：" + value));
            return 0;
        }

        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[矿脉重生] §f" + type.displayName() + " 的 "
                + key + " 已设置为 §e" + value + "§f，并已保存。"), true);
        // 取消接管时提醒清理：世界里先前生成的方块不会自己消失。
        // 只在它确实被接管过（历史里有记录）时提示，避免从没接管过也来一句。
        if (type.modded() && "enabled".equals(key) && !settings.isEnabled()
                && ConfigManager.everHandled().contains(type.id())) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7已取消接管，但世界里先前生成的方块不会自动消失——"
                            + "请用 §e/veinrebirth refresh <半径> clean §7清除残留。"), false);
        }
        return 1;
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value);
    }

    // ------------------------------------------------------------------ preset

    private static CompletableFuture<Suggestions> suggestPresets(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Preset.IDS, builder);
    }

    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6===== §e预设方案 §7（点击条目即可套用）§6====="), false);
        for (Preset preset : Preset.values()) {
            String text = "§e" + preset.displayName() + " §7(" + preset.id() + ") §8" + preset.description();
            String command = "/veinrebirth preset " + preset.id();
            Component line = Component.literal(text).withStyle(Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("§7点击套用「" + preset.displayName() + "」"))));
            source.sendSuccess(() -> line, false);
        }
        source.sendSuccess(() -> Component.literal(
                "§7预设以出厂值为基准做倍率，反复套用同一个结果一致；未启用的矿物不会被改动。"), false);
        source.sendSuccess(() -> Component.literal(
                "§7套用后已有区块请用 §e/veinrebirth refresh 4 clean §7重建。"), false);
        return Preset.values().length;
    }

    private static int presetApply(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "name");
        Preset preset = Preset.byId(raw);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "未知预设：" + raw + "，可用：" + String.join(" / ", Preset.IDS)));
            return 0;
        }
        Preset.apply(preset);
        ConfigManager.save();
        ConfigManager.syncToAll();

        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f已套用预设 §e" + preset.displayName()
                + "§f：" + preset.description()), true);
        if (preset == Preset.VANILLA) {
            source.sendSuccess(() -> Component.literal(
                    "§7注意：「原版体验」的语义是恢复出厂，未启用的模组矿物也一并回到了「不接管」状态。"), false);
        }
        source.sendSuccess(() -> Component.literal(
                "§7新生成的区块立即生效；已有区块请用 §e/veinrebirth refresh 4 clean §7重建。"), false);
        return 1;
    }

    // ------------------------------------------------------------------ share

    private static int shareExport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String code;
        try {
            code = ShareCode.encode();
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Share encode failed", t);
            source.sendFailure(Component.literal("§c[矿脉重生] 生成分享码失败："
                    + t.getClass().getSimpleName()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§6===== §e分享码 §7（" + code.length()
                + " 字符）§6====="), false);

        // 必须写清楚「按 T」：原版只在聊天栏打开着的时候才处理鼠标点击，而玩家回车执行命令后
        // 聊天栏会自动关闭 —— 此时分享码只是 HUD 左下角的一行贴纸，鼠标点上去毫无反应。
        // 不写这句，玩家十有八九会去点屏幕上那行字，然后以为复制功能是坏的。
        source.sendSuccess(() -> Component.literal(
                "§7按 §fT §7打开聊天栏，点击下面的 §b蓝色分享码§7 或 §b[复制]§7 按钮即可复制完整码。"), false);

        // 码太长时聊天栏里只显示开头，但点击复制的始终是完整码
        String display = code.length() <= CHAT_SAFE_LENGTH
                ? code
                : code.substring(0, PREVIEW_LENGTH) + "§8…（余下 "
                        + (code.length() - PREVIEW_LENGTH) + " 字符已省略）";
        // 前缀按钮与码共用同一个可点击 Style，整行点哪儿都算
        Style copyStyle = Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("§7点击复制完整分享码（" + code.length() + " 字符）")));
        Component clickable = Component.literal("§b§n[复制]§r ")
                .withStyle(copyStyle)
                .append(Component.literal("§b" + display).withStyle(copyStyle));
        source.sendSuccess(() -> clickable, false);

        if (code.length() > CHAT_SAFE_LENGTH) {
            source.sendSuccess(() -> Component.literal(
                    "§7码较长，直接粘进聊天栏会被截断（原版上限 256 字符），用上面点击复制或下面的文件。"), false);
        }

        try {
            Path saved = ShareCode.writeToFile(code);
            source.sendSuccess(() -> Component.literal("§7已写入文件：§f" + saved), false);
        } catch (Exception e) {
            VeinRebirthMod.LOGGER.warn("[VeinRebirth] Failed to write share file", e);
            source.sendSuccess(() -> Component.literal("§7（写入分享码文件失败，用上面的点击复制即可）"), false);
        }

        source.sendSuccess(() -> Component.literal(
                "§7自己要用也可以走 §e配置界面 → 预设方案 / 分享码 →「复制此码」§7，一键进剪贴板，不经过聊天栏。"), false);
        source.sendSuccess(() -> Component.literal(
                "§7对方用法：粘进「配置界面 → 预设方案 / 分享码」的输入框点导入，"), false);
        source.sendSuccess(() -> Component.literal(
                "§7或执行 §e/veinrebirth share import <码>§7；把文件放进对方 config/ 后可用 §e/veinrebirth share import file§7。"), false);
        return 1;
    }

    private static int shareImport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String raw = StringArgumentType.getString(ctx, "code").trim();
        boolean fromFile = "file".equalsIgnoreCase(raw);

        String code = raw;
        if (fromFile) {
            try {
                code = ShareCode.readFromFile();
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("§c[矿脉重生] " + e.getMessage()));
                return 0;
            } catch (Exception e) {
                source.sendFailure(Component.literal("§c[矿脉重生] 读取分享码文件失败："
                        + e.getClass().getSimpleName()));
                return 0;
            }
        }

        ShareCode.Result result;
        try {
            result = ShareCode.importCode(code);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("§c[矿脉重生] 导入失败：" + e.getMessage()));
            if (!fromFile && raw.length() > CHAT_SAFE_LENGTH) {
                source.sendSuccess(() -> Component.literal(
                        "§7分享码较长时聊天栏会自动截断。建议改用 `§e/veinrebirth share import file§7`，"
                                + "或在配置界面里粘贴导入。"), false);
            }
            return 0;
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Share import failed", t);
            source.sendFailure(Component.literal("§c[矿脉重生] 导入失败：" + t.getClass().getSimpleName()));
            return 0;
        }

        ConfigManager.save();
        ConfigManager.syncToAll();
        source.sendSuccess(() -> Component.literal("§a[矿脉重生] §f分享码导入成功：" + result.summary() + "。"), true);
        source.sendSuccess(() -> Component.literal(
                "§7新生成的区块立即生效；已有区块请用 §e/veinrebirth refresh 4 clean §7重建。"), false);
        return result.applied();
    }
}
