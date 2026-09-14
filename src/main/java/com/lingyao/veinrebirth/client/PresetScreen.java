package com.lingyao.veinrebirth.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lingyao.veinrebirth.ConfigManager;
import com.lingyao.veinrebirth.ConfigSyncPacket;
import com.lingyao.veinrebirth.NetworkHandler;
import com.lingyao.veinrebirth.Preset;
import com.lingyao.veinrebirth.ShareCode;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 预设方案与分享码界面（中文、原版风格）。
 * <p>
 * <b>上半</b>是内置预设：点一下就把整套数值换成某种风格。预设以出厂值为基准做倍率，
 * 所以反复点同一个预设结果一致；未启用的矿物（例如默认不接管的模组矿物）不会被改动。
 * <p>
 * <b>下半</b>是分享码：「复制此码」把当前整套配置压成一段码放进剪贴板，发给别人即可；
 * 把别人的码粘进输入框点「导入此码」就能整套套用。输入框平时是空的（它只负责"接收"），
 * 自己的码不需要手动去框里选——按钮直接复制。
 * <p>
 * 布局按可用空间算出来，最小逻辑分辨率 320×240 也不会重叠。
 */
@OnlyIn(Dist.CLIENT)
public class PresetScreen extends Screen {

    private static final int PANEL_BG = 0xC0101010;
    private static final int PANEL_BORDER = 0xFF5A5A5A;
    private static final int COLOR_TITLE = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9A9A9A;
    private static final int COLOR_HEADER = 0xFFFFC060;

    /** 预设按钮按 2 列排，5 个预设占 3 行（最后一行右边留空）。 */
    private static final int PRESET_COLS = 2;
    private static final int PRESET_ROWS = 3;

    private final Screen parent;

    private EditBox codeBox;
    private final Map<Button, Preset> presetButtons = new LinkedHashMap<>();
    private final List<Button> presetOrder = new ArrayList<>();

    private String status = "";
    private long statusTime;
    private Preset hoveredPreset;

    /** 当前配置的分享码长度（面板标题上显示），在 init / 套用预设 / 导入后刷新。 */
    private int codeLength;

    // ---------------- 布局 ----------------
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rowH;
    private int rowStep;
    private int bottomY;

    public PresetScreen(Screen parent) {
        super(Component.literal("预设方案与分享码"));
        this.parent = parent;
    }

    // ================================================================== 初始化

    @Override
    protected void init() {
        ConfigManager.ensureLoaded();

        int contentW = Mth.clamp(this.width - 16, 240, 380);
        this.panelW = contentW;
        this.panelX = this.width / 2 - contentW / 2;

        int headH = 14;
        int gapA = 12;
        int codeLabelH = 11;
        int codeBoxH = 16;
        int gapB = 6;
        int hintH = 10;

        int available = this.height - 62;

        this.rowH = 20;
        this.rowStep = 23;
        int fixed = headH + PRESET_ROWS * this.rowStep + gapA + codeLabelH + codeBoxH + gapB + hintH;
        if (fixed + this.rowH > available) {
            this.rowH = 18;
            this.rowStep = 20;
            fixed = headH + PRESET_ROWS * this.rowStep + gapA + codeLabelH + codeBoxH + gapB + hintH;
        }
        if (fixed + this.rowH > available) {
            this.rowH = 16;
            this.rowStep = 17;
            fixed = headH + PRESET_ROWS * this.rowStep + gapA + codeLabelH + codeBoxH + gapB + hintH;
        }
        this.panelH = Math.min(fixed + this.rowH, Math.max(120, available));
        this.panelY = Math.max(10, (this.height - (this.panelH + 34)) / 2 + 8);

        initPresetButtons(headH);
        initCodeSection(headH, gapA, codeLabelH, codeBoxH, gapB, hintH);

        // 底部「返回」
        this.bottomY = this.panelY + this.panelH + 6;
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(this.width / 2 - 50, this.bottomY, 100, 20).build());
    }

    private void initPresetButtons(int headH) {
        this.presetButtons.clear();
        this.presetOrder.clear();

        int colW = (this.panelW - 12) / PRESET_COLS;
        int startY = this.panelY + headH;
        Preset[] presets = Preset.values();

        for (int i = 0; i < presets.length; i++) {
            Preset preset = presets[i];
            int row = i / PRESET_COLS;
            int col = i % PRESET_COLS;
            // 最后一个预设独占整行，视觉上收尾
            boolean full = (i == presets.length - 1) && (presets.length % PRESET_COLS != 0);
            int x = this.panelX + 4 + (full ? 0 : col * (colW + 4));
            int w = full ? this.panelW - 8 : colW;
            int y = startY + row * this.rowStep;

            Button button = this.addRenderableWidget(Button.builder(
                    Component.literal(preset.displayName()), b -> this.applyPreset(preset))
                    .bounds(x, y, w, this.rowH).build());
            this.presetButtons.put(button, preset);
            this.presetOrder.add(button);
        }
    }

    private void initCodeSection(int headH, int gapA, int codeLabelH, int codeBoxH, int gapB, int hintH) {
        int boxY = this.panelY + headH + PRESET_ROWS * this.rowStep + gapA + codeLabelH;
        // 输入框只用来「粘贴别人的码」，所以留空并给提示 ——
        // 自己的码点一下「复制此码」就进剪贴板了，不需要用户自己去框里选中复制
        this.codeBox = new EditBox(this.font, this.panelX + 4, boxY, this.panelW - 8, codeBoxH,
                Component.literal("分享码"));
        this.codeBox.setMaxLength(8192);
        this.codeBox.setHint(Component.literal("在此粘贴别人的分享码，然后点「导入此码」…"));
        this.addRenderableWidget(this.codeBox);

        int buttonY = boxY + codeBoxH + gapB;
        int btnW = (this.panelW - 16) / 3;
        this.addRenderableWidget(Button.builder(Component.literal("复制此码"), b -> this.copyCode())
                .bounds(this.panelX + 4, buttonY, btnW, this.rowH).build());
        this.addRenderableWidget(Button.builder(Component.literal("导入此码"), b -> this.importCode())
                .bounds(this.panelX + 8 + btnW, buttonY, btnW, this.rowH).build());
        this.addRenderableWidget(Button.builder(Component.literal("清空输入框"), b -> this.clearInput())
                .bounds(this.panelX + 12 + btnW * 2, buttonY, btnW, this.rowH).build());

        refreshCodeLength();
    }

    // ================================================================== 行为

    private void applyPreset(Preset preset) {
        Preset.apply(preset);
        this.saveAndSync();
        refreshCodeLength();
        this.setStatus("§a已套用预设：" + preset.displayName());
    }

    private void copyCode() {
        if (this.minecraft == null) {
            return;
        }
        String code = ShareCode.encode();
        this.minecraft.keyboardHandler.setClipboard(code);
        this.codeLength = code.length();
        this.setStatus("§a分享码已复制到剪贴板（" + code.length() + " 字符），直接发给别人即可");
    }

    private void importCode() {
        String raw = this.codeBox.getValue();
        if (raw == null || raw.isBlank()) {
            this.setStatus("§c请先把分享码粘贴到上面的输入框里");
            return;
        }
        try {
            ShareCode.Result result = ShareCode.importCode(raw);
            this.saveAndSync();
            this.codeBox.setValue("");
            refreshCodeLength();
            this.setStatus("§a导入成功：" + result.summary());
        } catch (IllegalArgumentException e) {
            this.setStatus("§c导入失败：" + e.getMessage());
        } catch (Exception e) {
            this.setStatus("§c导入失败：" + e.getClass().getSimpleName());
        }
    }

    private void clearInput() {
        this.codeBox.setValue("");
        this.setStatus("§7已清空输入框");
    }

    /** 重新算一遍当前配置的码长（面板标题要显示）。编码本身很便宜，但也没必要每帧算。 */
    private void refreshCodeLength() {
        try {
            this.codeLength = ShareCode.encode().length();
        } catch (Throwable t) {
            this.codeLength = 0;
        }
    }

    private void saveAndSync() {
        ConfigManager.save();
        if (this.minecraft != null && !this.minecraft.hasSingleplayerServer()) {
            NetworkHandler.sendToServer(new ConfigSyncPacket(ConfigManager.serialize()));
        }
    }

    private void setStatus(String text) {
        this.status = text;
        this.statusTime = System.currentTimeMillis();
    }

    // ================================================================== 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        drawPanel(graphics, this.panelX, this.panelY, this.panelW, this.panelH);

        // 屏幕标题（面板上方）——原版 drawString 不做裁剪，标题短，安全
        graphics.drawCenteredString(this.font, this.title, this.width / 2,
                Math.max(4, this.panelY - 12), COLOR_TITLE);

        // 面板标题 + 预设区标题
        graphics.drawString(this.font, "预设方案", this.panelX + 4, this.panelY + 4, COLOR_TITLE, false);

        // 分享码区标题（右侧显示当前配置的码长，让玩家知道复制出来的有多长）
        int labelY = this.panelY + 14 + PRESET_ROWS * this.rowStep + 12;
        graphics.drawString(this.font, "分享码", this.panelX + 4, labelY, COLOR_HEADER, false);
        String codeHint = "§8当前配置共 " + this.codeLength + " 字符";
        graphics.drawString(this.font, codeHint,
                this.panelX + this.panelW - 5 - this.font.width(codeHint),
                labelY, COLOR_HINT, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 状态提示（窄屏下 320×240 只剩约 22px，所以折行结果要按可用高度截断）
        int statusY = this.bottomY + 22;
        if (!this.status.isEmpty() && System.currentTimeMillis() - this.statusTime < 6000L) {
            drawWrapped(graphics, this.status, this.width / 2, statusY);
        } else {
            graphics.drawCenteredString(this.font, "§7预设以出厂值为基准，反复点同一个结果一致",
                    this.width / 2, statusY, COLOR_HINT);
        }

        // 悬停预设时展示说明
        this.hoveredPreset = null;
        for (Map.Entry<Button, Preset> entry : this.presetButtons.entrySet()) {
            if (entry.getKey().isMouseOver(mouseX, mouseY)) {
                this.hoveredPreset = entry.getValue();
                break;
            }
        }
        if (this.hoveredPreset != null) {
            // 必须放进多个 Component 再交给 tooltip：单条文本里的 '\n' 走的是"单行"渲染路径，
            // 会被当成普通字符画成一个缺失字形的空心方块（看着就像按钮上多了个小图标）。
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§e" + this.hoveredPreset.displayName()));
            for (String line : wrap(this.hoveredPreset.description(), 24).split("\n")) {
                if (!line.isEmpty()) {
                    lines.add(Component.literal("§7" + line));
                }
            }
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    /** 状态文字可能很长，按宽度折行居中显示，并限制在屏幕内。 */
    private void drawWrapped(GuiGraphics graphics, String text, int centerX, int y) {
        String[] lines = wrap(text, 46).split("\n");
        for (int i = 0; i < lines.length && y + i * 11 <= this.height - 10; i++) {
            graphics.drawCenteredString(this.font, lines[i], centerX, y + i * 11, 0xFFFFFF);
        }
    }

    /** 纯文本按字数粗略折行（中文按等宽估算，够用）。 */
    private static String wrap(String text, int perLine) {
        StringBuilder sb = new StringBuilder(text.length());
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == '\n') {
                count = 0;
                continue;
            }
            if (++count >= perLine) {
                sb.append('\n');
                count = 0;
            }
        }
        return sb.toString();
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
