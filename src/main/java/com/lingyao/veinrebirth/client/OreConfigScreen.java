package com.lingyao.veinrebirth.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.lingyao.veinrebirth.ConfigManager;
import com.lingyao.veinrebirth.ConfigSyncPacket;
import com.lingyao.veinrebirth.NetworkHandler;
import com.lingyao.veinrebirth.OreSettings;
import com.lingyao.veinrebirth.OreType;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * 矿脉重生界面（中文、原版风格）。
 * <p>
 * <b>左侧</b>是可垂直滚动的矿物列表：支持鼠标滚轮、拖动滚动条，顶部有搜索框，
 * 按「原版矿物 / 各模组」分节显示，禁用的矿物灰显，悬停可看完整方块 id。
 * 列表用 scissor 裁剪，行数再多也不会溢出面板。
 * <p>
 * <b>右侧</b>用滑块调整「生成数量 / 生成规模 / 生成权重 / 最低高度 / 最高高度」，
 * 拖动即时生效（新生成的区块立刻使用新数值），点「保存配置」写回
 * config/veinrebirth-ores.toml。右下角的「预设方案 / 分享码」按钮打开
 * {@link PresetScreen}，可以一键换整套风格，或把当前配置生成分享码发给别人。
 * <p>
 * 布局全部按可用空间算出来，最小逻辑分辨率 320×240 也不会重叠。
 */
@OnlyIn(Dist.CLIENT)
public class OreConfigScreen extends Screen {

    private static final int PANEL_BG = 0xC0101010;
    private static final int PANEL_BORDER = 0xFF5A5A5A;
    private static final int COLOR_TITLE = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9A9A9A;
    private static final int COLOR_DIRTY = 0xFFFFAA00;
    private static final int COLOR_HEADER = 0xFFFFC060;
    private static final int COLOR_TEXT_ON = 0xFFDCDCDC;
    private static final int COLOR_TEXT_OFF = 0xFF7C7C7C;
    private static final int COLOR_SELECTED_BG = 0xFF2E5C8A;
    private static final int COLOR_HOVER_BG = 0x40FFFFFF;
    private static final int COLOR_DOT_ON = 0xFF57D75A;
    private static final int COLOR_DOT_TAKEOVER = 0xFF4FA3E3;
    private static final int COLOR_DOT_OFF = 0xFF5A5A5A;

    /** 右侧内容行数：开关 + 数量 + 规模 + 权重 + (最低/最高) + 矿脉 + 保存行 + 预设行。 */
    private static final int RIGHT_ROWS = 8;

    /** 列表行高与滚动条宽度。 */
    private static final int ROW_H = 13;
    private static final int SCROLLBAR_W = 6;
    /** 搜索框高度。 */
    private static final int SEARCH_H = 12;

    private final Screen parent;

    // ---------------- 右侧控件 ----------------
    private IntSlider countSlider;
    private IntSlider sizeSlider;
    private IntSlider weightSlider;
    private IntSlider minYSlider;
    private IntSlider maxYSlider;
    private Button toggleButton;
    private Button veinButton;
    private EditBox searchBox;

    private OreType selected;

    private boolean dirty;
    private String status = "";
    private long statusTime;

    // ---------------- 布局（init 时算好） ----------------
    private int leftX;
    private int topY;
    private int listW;
    private int rightX;
    private int rightW;
    private int panelH;
    private int rowH;
    private int rowStep;
    private int doneY;

    /** 左侧列表面板的可用区域。 */
    private int listTop;
    private int listBottom;

    // ---------------- 列表状态 ----------------
    private final List<Row> rows = new ArrayList<>();
    private String filter = "";
    private int scroll;
    private boolean draggingScrollbar;
    private int dragGrab;
    private OreType hoveredOre;
    private int builtForOreCount = -1;

    /** 列表里的一行：要么是分组标题，要么是一种矿物。 */
    private static final class Row {
        final String header;
        final OreType ore;

        Row(String header) {
            this.header = header;
            this.ore = null;
        }

        Row(OreType ore) {
            this.header = null;
            this.ore = ore;
        }
    }

    public OreConfigScreen(Screen parent) {
        super(Component.literal("矿脉重生"));
        this.parent = parent;
        this.selected = OreType.values()[0];
    }

    // ================================================================== 初始化

    @Override
    protected void init() {
        ConfigManager.ensureLoaded();

        int contentW = Math.min(this.width - 20, 520);
        this.leftX = this.width / 2 - contentW / 2;
        // 列表要放得下矿物名，宽度按比例给，并夹在合理区间里
        this.listW = Mth.clamp((int) (contentW * 0.42F), 124, 220);
        this.rightX = this.leftX + this.listW + 10;
        this.rightW = contentW - this.listW - 10;
        int available = this.height - 78;

        // 行高按可用空间自适应：优先 20，不够就压到 18 / 16
        this.rowH = 20;
        this.rowStep = 22;
        int needed = 16 + RIGHT_ROWS * this.rowStep;
        if (needed > available) {
            this.rowH = 18;
            this.rowStep = 20;
            needed = 16 + RIGHT_ROWS * this.rowStep;
        }
        if (needed > available) {
            this.rowH = 16;
            this.rowStep = 17;
            needed = 16 + RIGHT_ROWS * this.rowStep;
        }
        this.panelH = Math.min(needed, Math.max(120, available));
        this.topY = Math.max(14, (this.height - (this.panelH + 58)) / 2 + 10);

        initOreList();

        if (this.selected == null || !isRegistered(this.selected)) {
            this.selected = OreType.values()[0];
        }

        initRightPanel();
        this.syncWidgetsFromConfig();
    }

    private static boolean isRegistered(OreType type) {
        for (OreType candidate : OreType.values()) {
            if (candidate == type) {
                return true;
            }
        }
        return false;
    }

    private void initOreList() {
        // 搜索框：列表顶部，输入即时过滤
        this.searchBox = new EditBox(this.font, this.leftX + 3, this.topY + 16, this.listW - 6, SEARCH_H,
                Component.literal("搜索矿物"));
        this.searchBox.setMaxLength(48);
        this.searchBox.setHint(Component.literal("搜索…"));
        this.searchBox.setValue(this.filter);
        this.searchBox.setResponder(text -> {
            this.filter = text == null ? "" : text;
            this.scroll = 0;
            rebuildRows();
        });
        this.addRenderableWidget(this.searchBox);

        this.listTop = this.topY + 16 + SEARCH_H + 2;
        this.listBottom = this.topY + this.panelH - 3;
        rebuildRows();
    }

    private void initRightPanel() {
        int sx = this.rightX + 4;
        int sw = this.rightW - 8;
        int half = (sw - 6) / 2;
        OreSettings settings = ConfigManager.get(this.selected);

        int y = this.topY + 16;

        // 第 1 行：启用 / 禁用
        this.toggleButton = this.addRenderableWidget(Button.builder(Component.literal(""), b -> {
            OreSettings current = ConfigManager.get(this.selected);
            current.setEnabled(!current.isEnabled());
            this.markDirty();
            this.updateToggleLabel();
            rebuildRows();
            this.hintAfterToggle(current);
        }).bounds(sx, y, sw, this.rowH).build());

        // 第 2~4 行：数量 / 规模 / 权重
        y += this.rowStep;
        this.countSlider = this.addRenderableWidget(new IntSlider(sx, y, sw, this.rowH, "生成数量",
                OreType.COUNT_MIN, OreType.COUNT_MAX, settings.getCount(),
                v -> this.change(s -> s.setCount(v))));
        y += this.rowStep;
        this.sizeSlider = this.addRenderableWidget(new IntSlider(sx, y, sw, this.rowH, "生成规模",
                OreType.SIZE_MIN, OreType.SIZE_MAX, settings.getSize(),
                v -> this.change(s -> s.setSize(v))));
        y += this.rowStep;
        this.weightSlider = this.addRenderableWidget(new IntSlider(sx, y, sw, this.rowH, "生成权重(%)",
                OreType.WEIGHT_MIN, OreType.WEIGHT_MAX, settings.getWeight(),
                v -> this.change(s -> s.setWeight(v))));

        // 第 5 行：最低高度 | 最高高度（并排，省一行高度）
        y += this.rowStep;
        this.minYSlider = this.addRenderableWidget(new IntSlider(sx, y, half, this.rowH, "最低高度",
                OreType.Y_MIN, OreType.Y_MAX, settings.getMinY(),
                v -> this.change(s -> s.setMinY(v))));
        this.maxYSlider = this.addRenderableWidget(new IntSlider(sx + half + 6, y, half, this.rowH, "最高高度",
                OreType.Y_MIN, OreType.Y_MAX, settings.getMaxY(),
                v -> this.change(s -> s.setMaxY(v))));

        // 第 6 行：原版大型矿脉开关
        y += this.rowStep;
        this.veinButton = this.addRenderableWidget(Button.builder(Component.literal(""), b -> {
            ConfigManager.setVeinOresEnabled(!ConfigManager.isVeinOresEnabled());
            this.markDirty();
            this.updateVeinLabel();
        }).bounds(sx, y, sw, this.rowH).build());

        // 第 7 行：保存 / 重载 / 默认
        y += this.rowStep;
        int buttonW = (sw - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.literal("保存配置"), b -> this.applyAndSave())
                .bounds(sx, y, buttonW, this.rowH).build());
        this.addRenderableWidget(Button.builder(Component.literal("重新载入"), b -> this.reloadFromFile())
                .bounds(sx + buttonW + 4, y, buttonW, this.rowH).build());
        this.addRenderableWidget(Button.builder(Component.literal("恢复默认"), b -> this.restoreDefaults())
                .bounds(sx + (buttonW + 4) * 2, y, buttonW, this.rowH).build());

        // 第 8 行：预设方案与分享码
        y += this.rowStep;
        this.addRenderableWidget(Button.builder(Component.literal("预设方案 / 分享码…"),
                b -> this.openPresets()).bounds(sx, y, sw, this.rowH).build());

        // 底部「完成」
        this.doneY = this.topY + this.panelH + 6;
        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> this.onClose())
                .bounds(this.width / 2 - 60, this.doneY, 120, 20).build());
    }

    // ================================================================== 列表数据

    private void rebuildRows() {
        this.rows.clear();
        String keyword = this.filter == null ? "" : this.filter.trim().toLowerCase(Locale.ROOT);

        if (!keyword.isEmpty()) {
            for (OreType type : OreType.values()) {
                if (type.id().toLowerCase(Locale.ROOT).contains(keyword)
                        || type.displayName().toLowerCase(Locale.ROOT).contains(keyword)) {
                    this.rows.add(new Row(type));
                }
            }
            this.builtForOreCount = OreType.registrySize();
            clampScroll();
            return;
        }

        // 原版矿物一节
        boolean headerAdded = false;
        for (OreType type : OreType.values()) {
            if (type.modded()) {
                continue;
            }
            if (!headerAdded) {
                this.rows.add(new Row("原版矿物"));
                headerAdded = true;
            }
            this.rows.add(new Row(type));
        }

        // 识别到的模组矿物按来源模组分节
        String lastNamespace = null;
        for (OreType type : OreType.values()) {
            if (!type.modded()) {
                continue;
            }
            if (!type.namespace().equals(lastNamespace)) {
                lastNamespace = type.namespace();
                this.rows.add(new Row("模组 · " + namespaceLabel(lastNamespace)));
            }
            this.rows.add(new Row(type));
        }

        this.builtForOreCount = OreType.registrySize();
        clampScroll();
    }

    /** 用模组的显示名代替裸命名空间，取不到时退回命名空间本身。 */
    private static String namespaceLabel(String namespace) {
        try {
            var container = ModList.get().getModContainerById(namespace);
            if (container.isPresent()) {
                String name = container.get().getModInfo().getDisplayName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // 模组列表不可用（例如极早期阶段）时退回命名空间
        }
        return namespace;
    }

    private int contentHeight() {
        return this.rows.size() * ROW_H;
    }

    private int maxScroll() {
        int viewport = this.listBottom - this.listTop;
        return Math.max(0, contentHeight() - viewport);
    }

    private void clampScroll() {
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
    }

    /** 命中测试：返回鼠标所在行下标，不在列表内返回 -1。 */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < this.leftX || mouseX >= this.leftX + this.listW - SCROLLBAR_W) {
            return -1;
        }
        if (mouseY < this.listTop || mouseY >= this.listBottom) {
            return -1;
        }
        int index = (int) ((mouseY - this.listTop + this.scroll) / ROW_H);
        return (index >= 0 && index < this.rows.size()) ? index : -1;
    }

    private boolean inScrollbar(double mouseX, double mouseY) {
        return maxScroll() > 0
                && mouseX >= this.leftX + this.listW - SCROLLBAR_W && mouseX < this.leftX + this.listW
                && mouseY >= this.listTop && mouseY < this.listBottom;
    }

    private void selectOre(OreType type) {
        if (this.selected == type) {
            return;
        }
        this.selected = type;
        this.syncWidgetsFromConfig();
    }

    // ================================================================== 交互

    /** 滑块改动：直接写入运行时数值，立即对新生成的区块生效。 */
    private void change(java.util.function.Consumer<OreSettings> mutator) {
        mutator.accept(ConfigManager.get(this.selected));
        this.markDirty();
    }

    private void markDirty() {
        this.dirty = true;
        this.status = "";
    }

    private void applyAndSave() {
        ConfigManager.save();
        this.pushToServerIfNeeded();
        this.dirty = false;
        this.setStatus("§a已保存到 config/" + ConfigManager.FILE_NAME);
    }

    private void reloadFromFile() {
        ConfigManager.load();
        this.syncWidgetsFromConfig();
        rebuildRows();
        this.dirty = false;
        this.setStatus("§a已重新读取配置文件");
    }

    private void restoreDefaults() {
        ConfigManager.resetToDefaults();
        this.syncWidgetsFromConfig();
        rebuildRows();
        this.dirty = true;
        // 恢复默认会关掉所有模组矿物的接管，但它们先前生成的方块还留在世界里。
        // 接管历史会保住这些方块的清理依据，这里把用法直接告诉玩家。
        if (ConfigManager.everHandled().isEmpty()) {
            this.setStatus("§e已恢复默认值（尚未保存）");
        } else {
            this.setStatus("§e已恢复默认；先前的模组矿物残留请用 /veinrebirth refresh <半径> clean 清除");
        }
    }

    /**
     * 取消某个模组矿物的接管时，提醒它先前生成的方块不会自己消失。
     * 只在它确实被接管过（接管历史里有记录）时提示，避免从没接管过也来一句。
     */
    private void hintAfterToggle(OreSettings settings) {
        if (this.selected != null && this.selected.modded() && !settings.isEnabled()
                && ConfigManager.everHandled().contains(this.selected.id())) {
            this.setStatus("§7已取消接管；世界里先前生成的方块可用 /veinrebirth refresh <半径> clean 清除");
        }
    }

    private void pushToServerIfNeeded() {
        if (this.minecraft != null && !this.minecraft.hasSingleplayerServer()) {
            NetworkHandler.sendToServer(new ConfigSyncPacket(ConfigManager.serialize()));
        }
    }

    /**
     * 打开预设 / 分享码界面。
     * <p>
     * 那边的改动会直接写进运行时数值，返回时本界面 {@link #init()} 会重新执行，
     * 滑块与列表随之刷新，所以这里不需要额外回传什么。
     */
    private void openPresets() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new PresetScreen(this));
        }
    }

    private void setStatus(String text) {
        this.status = text;
        this.statusTime = System.currentTimeMillis();
    }

    private void syncWidgetsFromConfig() {
        OreSettings settings = ConfigManager.get(this.selected);
        this.countSlider.setIntValue(settings.getCount());
        this.sizeSlider.setIntValue(settings.getSize());
        this.weightSlider.setIntValue(settings.getWeight());
        this.minYSlider.setIntValue(settings.getMinY());
        this.maxYSlider.setIntValue(settings.getMaxY());
        this.updateToggleLabel();
        this.updateVeinLabel();
    }

    private void updateToggleLabel() {
        boolean on = ConfigManager.get(this.selected).isEnabled();
        // 文案按最窄情况（320×240 时按钮宽约 156px）压过：中文按 9px 估算，
        // 两种都控制在 ~130px 以内，避免原版按钮不做裁剪导致文字溢到相邻控件上。
        String label;
        if (this.selected.modded()) {
            label = on ? "§f接管生成：§a已接管§8（点击切换）" : "§f接管生成：§c未接管§8（点击切换）";
        } else {
            label = on ? "§f生成开关：§a已启用§8（点击切换）" : "§f生成开关：§c已禁用§8（点击切换）";
        }
        this.toggleButton.setMessage(Component.literal(label));
    }

    private void updateVeinLabel() {
        boolean on = ConfigManager.isVeinOresEnabled();
        this.veinButton.setMessage(Component.literal(
                "§f原版大型矿脉：" + (on ? "§a保留" : "§c已清除") + "§8（点击）"));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0
                && mouseX >= this.leftX && mouseX < this.leftX + this.listW
                && mouseY >= this.listTop && mouseY < this.listBottom) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.round(delta * ROW_H * 2), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (inScrollbar(mouseX, mouseY)) {
                this.draggingScrollbar = true;
                this.dragGrab = (int) mouseY - thumbTop();
                return true;
            }
            int index = rowIndexAt(mouseX, mouseY);
            if (index >= 0) {
                OreType ore = this.rows.get(index).ore;
                if (ore != null) {
                    selectOre(ore);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            scrollThumbTo((int) mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private int trackHeight() {
        return this.listBottom - this.listTop;
    }

    private int thumbHeight() {
        int content = contentHeight();
        if (content <= 0) {
            return trackHeight();
        }
        return Math.max(10, trackHeight() * trackHeight() / content);
    }

    private int thumbTop() {
        int max = maxScroll();
        if (max <= 0) {
            return this.listTop;
        }
        return this.listTop + (trackHeight() - thumbHeight()) * this.scroll / max;
    }

    private void scrollThumbTo(int mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int travel = trackHeight() - thumbHeight();
        if (travel <= 0) {
            return;
        }
        int top = Mth.clamp(mouseY - this.dragGrab, this.listTop, this.listTop + travel);
        this.scroll = (top - this.listTop) * max / travel;
    }

    // ================================================================== 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 识别到新矿物（例如刚执行过 /veinrebirth scan）时刷新列表内容
        if (this.builtForOreCount != OreType.registrySize()) {
            rebuildRows();
        }

        this.renderBackground(graphics);

        drawPanel(graphics, this.leftX, this.topY, this.listW, this.panelH);
        drawPanel(graphics, this.rightX, this.topY, this.rightW, this.panelH);

        renderOreList(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);

        renderHeadings(graphics);

        // 底部提示：有状态消息时优先显示状态
        graphics.drawCenteredString(this.font,
                "§7配置文件：config/" + ConfigManager.FILE_NAME + "（可用记事本编辑）",
                this.width / 2, this.doneY + 24, COLOR_HINT);
        if (!this.status.isEmpty() && System.currentTimeMillis() - this.statusTime < 5000L) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, this.doneY + 36, 0xFFFFFF);
        } else if (this.doneY + 45 <= this.height) {
            graphics.drawCenteredString(this.font,
                    "§7新生成的区块立即生效；旧区块用 §f/veinrebirth refresh <半径>§7 刷新",
                    this.width / 2, this.doneY + 36, COLOR_HINT);
        }

        // 悬停提示放在最后，避免被其它元素盖住
        if (this.hoveredOre != null) {
            graphics.renderTooltip(this.font,
                    Component.literal(this.hoveredOre.blockId() + "  §7" + this.hoveredOre.group().displayName()
                            + (this.hoveredOre.modded() ? "  §7识别自模组" : "")),
                    mouseX, mouseY);
        }
    }

    /** 标题、面板标题、未保存标记。 */
    private void renderHeadings(GuiGraphics graphics) {
        int titleY = Math.max(4, this.topY - 12);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, titleY, COLOR_TITLE);

        // 左侧：列表标题 + 计数
        graphics.drawString(this.font, "矿物列表", this.leftX + 4, this.topY + 5, COLOR_TITLE, false);
        String count = "§7" + this.rows.size() + " 项";
        graphics.drawString(this.font, count,
                this.leftX + this.listW - 5 - this.font.width(count), this.topY + 5, COLOR_HINT, false);

        // 右侧：当前矿物。名称可能很长（模组矿物的译名），用 scissor 限制在面板标题条内，
        // 避免原版 drawString 不做裁剪、文字直接溢到面板外面去。
        OreType ore = this.selected;
        String name = "§f设置：§e" + ore.displayName();
        graphics.enableScissor(this.rightX + 1, this.topY, this.rightX + this.rightW - 1, this.topY + 15);
        try {
            graphics.drawString(this.font, name, this.rightX + 4, this.topY + 5, COLOR_TITLE, false);
            if (ore.modded()) {
                String id = "§8" + ore.blockId();
                int x = this.rightX + 4 + this.font.width(name) + 4;
                graphics.drawString(this.font, id, x, this.topY + 5, COLOR_HINT, false);
            }
        } finally {
            graphics.disableScissor();
        }

        if (this.dirty) {
            String text = "* 有未保存的修改";
            graphics.drawString(this.font, "§6" + text,
                    this.width - 4 - this.font.width(text), titleY, COLOR_DIRTY, false);
        }
    }

    /** 左侧可滚动列表：scissor 裁剪 + 只绘制可见行。 */
    private void renderOreList(GuiGraphics graphics, int mouseX, int mouseY) {
        this.hoveredOre = null;
        if (this.listBottom <= this.listTop || this.rows.isEmpty()) {
            return;
        }

        int clipRight = this.leftX + this.listW - SCROLLBAR_W;
        graphics.enableScissor(this.leftX + 1, this.listTop, clipRight, this.listBottom);
        try {
            int first = Math.max(0, this.scroll / ROW_H);
            int last = Math.min(this.rows.size() - 1, (this.scroll + trackHeight()) / ROW_H);
            for (int index = first; index <= last; index++) {
                Row row = this.rows.get(index);
                int y = this.listTop + index * ROW_H - this.scroll;

                if (row.header != null) {
                    graphics.drawString(this.font, row.header, this.leftX + 4, y + 2, COLOR_HEADER, false);
                    graphics.fill(this.leftX + 3, y + ROW_H - 1, clipRight - 2, y + ROW_H, 0x50FFC060);
                    continue;
                }

                OreType ore = row.ore;
                boolean selectedRow = ore == this.selected;
                boolean rowHovered = mouseX >= this.leftX && mouseX < clipRight
                        && mouseY >= Math.max(y, this.listTop) && mouseY < Math.min(y + ROW_H, this.listBottom);

                if (selectedRow) {
                    graphics.fill(this.leftX + 1, y, clipRight, y + ROW_H, COLOR_SELECTED_BG);
                } else if (rowHovered) {
                    graphics.fill(this.leftX + 1, y, clipRight, y + ROW_H, COLOR_HOVER_BG);
                }

                // 左侧状态色条：绿=启用 / 蓝=已接管模组矿物 / 灰=未启用
                boolean on = ConfigManager.get(ore).isEnabled();
                int dot = on ? (ore.modded() ? COLOR_DOT_TAKEOVER : COLOR_DOT_ON) : COLOR_DOT_OFF;
                graphics.fill(this.leftX + 2, y + 2, this.leftX + 4, y + ROW_H - 2, dot);

                int color = selectedRow ? 0xFFFFFFFF : (on ? COLOR_TEXT_ON : COLOR_TEXT_OFF);
                String label = ore.displayName();
                int textX = this.leftX + 7;
                graphics.drawString(this.font, label, textX, y + 2, color, false);

                // 右侧灰字标出来源模组（放得下才画，放不下会被裁剪掉，不会溢出）
                if (ore.modded()) {
                    String ns = ore.namespace();
                    int nsW = this.font.width(ns);
                    int nsX = clipRight - 4 - nsW;
                    if (nsX > textX + this.font.width(label) + 4) {
                        graphics.drawString(this.font, ns, nsX, y + 2, 0xFF6A8A9A, false);
                    }
                }

                if (rowHovered) {
                    this.hoveredOre = ore;
                }
            }
        } finally {
            graphics.disableScissor();
        }

        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (maxScroll() <= 0) {
            return;
        }
        int trackX = this.leftX + this.listW - SCROLLBAR_W;
        int top = thumbTop();
        graphics.fill(trackX, this.listTop, trackX + SCROLLBAR_W - 1, this.listBottom, 0x50000000);
        graphics.fill(trackX, top, trackX + SCROLLBAR_W - 1, top + thumbHeight(), 0xFF9A9A9A);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL_BG);
        graphics.fill(x, y, x + width, y + 1, PANEL_BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER);
        graphics.fill(x, y, x + 1, y + height, PANEL_BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, PANEL_BORDER);
    }

    // ================================================================== 生命周期

    @Override
    public void removed() {
        if (this.dirty) {
            ConfigManager.save();
            this.pushToServerIfNeeded();
            this.dirty = false;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
