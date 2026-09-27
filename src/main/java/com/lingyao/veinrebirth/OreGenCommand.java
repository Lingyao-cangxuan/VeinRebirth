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
import net.minecraft.network.chat.MutableComponent;
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
            source.sendSuccess(() -> Component.translatable(
                    "veinrebirth.cmd.selftest.pass", passed, total), false);
        } else {
            source.sendFailure(Component.translatable(
                    "veinrebirth.cmd.selftest.fail", failed, total));
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
            source.sendFailure(Component.translatable("veinrebirth.cmd.refresh.no_dimension"));
            return 0;
        }
        final ServerLevel targetLevel = level;
        BlockPos center = BlockPos.containing(source.getPosition());

        Component modeText = switch (mode) {
            case NONE -> Component.empty();
            case MANAGED -> Component.translatable("veinrebirth.cmd.refresh.mode.managed");
            case ALL -> Component.translatable("veinrebirth.cmd.refresh.mode.all");
        };
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.start",
                targetLevel.dimension().location().toString(), radius, modeText), false);

        ChunkRefresher.Result result;
        try {
            result = ChunkRefresher.refresh(level, center, radius, mode);
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Refresh failed", t);
            String message = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            source.sendFailure(Component.translatable("veinrebirth.cmd.refresh.failed", message));
            return 0;
        }

        ChunkRefresher.Result finalResult = result;
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.done",
                finalResult.chunksScanned(), finalResult.chunksTouched()), true);
        if (finalResult.oreBlocksRemoved() > 0) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.removed",
                    finalResult.oreBlocksRemoved()), false);
        }
        if (finalResult.veinBlocksRemoved() > 0) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.veins_removed",
                    finalResult.veinBlocksRemoved()), false);
        }
        if (mode == ChunkRefresher.CleanMode.ALL) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.clean_all_hint"), false);
        }
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.refresh.hint"), false);
        return finalResult.chunksTouched();
    }

    // ------------------------------------------------------------------ list / scan

    private static int list(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSourceStack source = ctx.getSource();
        String keyword = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        OreType[] all = OreType.values();
        int matched = 0;
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.list.header",
                all.length, OreType.moddedCount()), false);

        for (OreType type : all) {
            // 关键字同时匹配 id、中文名与当前语言下的名字（英文环境下输入 "tin" 也要能搜到锡矿）
            if (!keyword.isEmpty()
                    && !type.id().toLowerCase(Locale.ROOT).contains(keyword)
                    && !type.displayName().toLowerCase(Locale.ROOT).contains(keyword)
                    && !type.name().getString().toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            matched++;
            if (matched > LIST_LIMIT) {
                continue;
            }
            OreSettings settings = ConfigManager.get(type);
            Component mark = type.modded()
                    ? Component.translatable(settings.isEnabled()
                            ? "veinrebirth.cmd.list.mark.managed"
                            : "veinrebirth.cmd.list.mark.unmanaged")
                    : Component.empty();
            MutableComponent line = Component.literal("§f").append(mark).append(type.name())
                    .append(Component.literal(" §7(" + type.id() + ") §8"))
                    .append(type.group().title());
            if (!settings.willGenerate()) {
                line.append(Component.translatable("veinrebirth.cmd.list.not_generating"));
            }
            source.sendSuccess(() -> line, false);
        }

        if (matched == 0) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.list.empty", keyword), false);
            return 0;
        }
        if (matched > LIST_LIMIT) {
            int rest = matched - LIST_LIMIT;
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.list.truncated", rest), false);
        }
        return matched;
    }

    private static int scan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.scan.start"), false);
        int added;
        try {
            added = OreDiscoverer.rescan();
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Rescan failed", t);
            String message = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            source.sendFailure(Component.translatable("veinrebirth.cmd.scan.failed", message));
            return 0;
        }
        int total = OreType.moddedCount();
        if (added > 0) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.scan.added",
                    added, total), true);
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.scan.added_hint"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.scan.none", total), false);
        }
        if (!ConfigManager.isDetectModdedOres()) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.scan.disabled"), false);
        }
        return added;
    }

    // ------------------------------------------------------------------ reload / save / defaults

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.load();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.reload.done",
                ConfigManager.file().toString()), true);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.save.done",
                ConfigManager.file().toString()), true);
        return 1;
    }

    private static int defaults(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.resetToDefaults();
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.defaults.done"), true);
        return 1;
    }

    // ------------------------------------------------------------------ veins

    private static int veinsInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean on = ConfigManager.isVeinOresEnabled();
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.header"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.status",
                Component.translatable(on
                        ? "veinrebirth.cmd.veins.state.on"
                        : "veinrebirth.cmd.veins.state.off")), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.desc1"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.desc2"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.height",
                OreVeins.MIN_Y, OreVeins.MAX_Y), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.toggle"), false);
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
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.veins.bad_value", raw));
            return 0;
        }
        ConfigManager.setVeinOresEnabled(value);
        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.set",
                Component.translatable(value
                        ? "veinrebirth.cmd.veins.on"
                        : "veinrebirth.cmd.veins.off")), true);
        if (!value) {
            ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.veins.set_hint"), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ info

    private static int info(CommandContext<CommandSourceStack> ctx) {
        OreType type = OreType.byId(StringArgumentType.getString(ctx, "ore"));
        if (type == null) {
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.unknown_ore",
                    StringArgumentType.getString(ctx, "ore")));
            return 0;
        }
        OreSettings settings = ConfigManager.get(type);
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.header",
                type.name(), type.id()), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.enabled",
                String.valueOf(settings.isEnabled())), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.count",
                settings.getCount()), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.size",
                settings.getSize()), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.weight",
                settings.getWeight()), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.info.height",
                settings.getMinY(), settings.getMaxY()), false);
        return 1;
    }

    // ------------------------------------------------------------------ set

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String oreId = StringArgumentType.getString(ctx, "ore");
        OreType type = OreType.byId(oreId);
        if (type == null) {
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.unknown_ore", oreId));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
        String value = StringArgumentType.getString(ctx, "value");
        if (!KEYS.contains(key)) {
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.set.unknown_key",
                    key, String.join(", ", KEYS)));
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
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.set.bad_value", value));
            return 0;
        }

        ConfigManager.save();
        ConfigManager.syncToAll();
        ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.set.done",
                type.name(), key, value), true);
        // 取消接管时提醒清理：世界里先前生成的方块不会自己消失。
        // 只在它确实被接管过（历史里有记录）时提示，避免从没接管过也来一句。
        if (type.modded() && "enabled".equals(key) && !settings.isEnabled()
                && ConfigManager.everHandled().contains(type.id())) {
            ctx.getSource().sendSuccess(() -> Component.translatable("veinrebirth.cmd.set.unmanage_hint"),
                    false);
        }
        return 1;
    }

    /**
     * 分享码异常 → 玩家可读文案。
     * <p>
     * {@link ShareCode.ShareException} 自带翻译键，按玩家语言渲染；其它异常只能拿到原始消息，
     * 原样显示（总比显示 null 强）。
     */
    private static Component reasonOf(IllegalArgumentException e) {
        if (e instanceof ShareCode.ShareException share) {
            return share.text();
        }
        String message = e.getMessage();
        return Component.literal(message == null ? e.getClass().getSimpleName() : message);
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
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.preset.header"), false);
        for (Preset preset : Preset.values()) {
            String command = "/veinrebirth preset " + preset.id();
            Component line = Component.literal("§e").append(preset.title())
                    .append(Component.literal(" §7(" + preset.id() + ") §8"))
                    .append(preset.desc())
                    .withStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("veinrebirth.cmd.preset.click_hint",
                                            preset.title()))));
            source.sendSuccess(() -> line, false);
        }
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.preset.note.scale"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.preset.note.refresh"), false);
        return Preset.values().length;
    }

    private static int presetApply(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "name");
        Preset preset = Preset.byId(raw);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.translatable("veinrebirth.cmd.preset.unknown",
                    raw, String.join(" / ", Preset.IDS)));
            return 0;
        }
        Preset.apply(preset);
        ConfigManager.save();
        ConfigManager.syncToAll();

        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.preset.applied",
                preset.title(), preset.desc()), true);
        if (preset == Preset.VANILLA) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.preset.vanilla_note"), false);
        }
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.after_apply_hint"), false);
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
            source.sendFailure(Component.translatable("veinrebirth.cmd.share.encode_failed",
                    t.getClass().getSimpleName()));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.header",
                code.length()), false);

        // 必须写清楚「按 T」：原版只在聊天栏打开着的时候才处理鼠标点击，而玩家回车执行命令后
        // 聊天栏会自动关闭 —— 此时分享码只是 HUD 左下角的一行贴纸，鼠标点上去毫无反应。
        // 不写这句，玩家十有八九会去点屏幕上那行字，然后以为复制功能是坏的。
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.howto"), false);

        // 码太长时聊天栏里只显示开头，但点击复制的始终是完整码
        boolean shortened = code.length() > CHAT_SAFE_LENGTH;
        MutableComponent codeText = Component.literal("§b")
                .append(shortened ? code.substring(0, PREVIEW_LENGTH) : code);
        if (shortened) {
            codeText.append(Component.translatable("veinrebirth.cmd.share.truncated",
                    code.length() - PREVIEW_LENGTH));
        }
        // 前缀按钮与码共用同一个可点击 Style，整行点哪儿都算
        Style copyStyle = Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("veinrebirth.cmd.share.copy_hover", code.length())));
        Component clickable = Component.translatable("veinrebirth.cmd.share.copy_button")
                .withStyle(copyStyle)
                .append(codeText.withStyle(copyStyle));
        source.sendSuccess(() -> clickable, false);

        if (code.length() > CHAT_SAFE_LENGTH) {
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.long_hint"), false);
        }

        try {
            Path saved = ShareCode.writeToFile(code);
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.file_written",
                    saved.toString()), false);
        } catch (Exception e) {
            VeinRebirthMod.LOGGER.warn("[VeinRebirth] Failed to write share file", e);
            source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.file_failed"), false);
        }

        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.self_hint"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.other_hint"), false);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.other_hint2"), false);
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
                source.sendFailure(Component.translatable("veinrebirth.cmd.error", reasonOf(e)));
                return 0;
            } catch (Exception e) {
                source.sendFailure(Component.translatable("veinrebirth.cmd.share.read_failed",
                        e.getClass().getSimpleName()));
                return 0;
            }
        }

        ShareCode.Result result;
        try {
            result = ShareCode.importCode(code);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("veinrebirth.cmd.share.import_failed", reasonOf(e)));
            if (!fromFile && raw.length() > CHAT_SAFE_LENGTH) {
                source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.truncated_hint"), false);
            }
            return 0;
        } catch (Throwable t) {
            VeinRebirthMod.LOGGER.error("[VeinRebirth] Share import failed", t);
            source.sendFailure(Component.translatable("veinrebirth.cmd.share.import_failed",
                    t.getClass().getSimpleName()));
            return 0;
        }

        ConfigManager.save();
        ConfigManager.syncToAll();
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.share.import_ok",
                result.summary()), true);
        source.sendSuccess(() -> Component.translatable("veinrebirth.cmd.after_apply_hint"), false);
        return result.applied();
    }
}
