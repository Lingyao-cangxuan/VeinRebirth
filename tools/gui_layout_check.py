# -*- coding: utf-8 -*-
"""
OreConfigScreen 布局验算（不依赖游戏，纯算术）。

把 OreConfigScreen.init() 里的布局算法原样复刻到 Python，在 MC 允许的各种
「逻辑分辨率」下检查：
  1. 左右两块面板都在屏幕内，且互不重叠
  2. 左侧搜索框、列表面板都在左面板内，搜索框不与列表可视区重叠
  3. 列表可视区高度足够（至少 3 行），宽度足够放搜索框
  4. 右侧 7 行控件都在右面板内，不与面板底边 / 「完成」按钮重叠
  5. 控件文字（按 中文 9px / ASCII 6px 估算）不溢出控件宽度
     —— 原版控件文字不做裁剪，溢出会盖到相邻控件上

MC 的逻辑分辨率下限是 320x240（GUI 缩放会被自动夹紧到满足这个下限），
所以 320x240 是最坏情况，必须通过。
"""
import math

RIGHT_ROWS = 7          # 右侧：开关 + 3 滑块 + 高度双滑块 + 矿脉开关 + 按钮行
ROW_H = 13              # 列表行高
SEARCH_H = 12           # 搜索框高度
CJK = 9.0               # 一个汉字（unifont 回退字形）的推进宽度
ASCII = 6.0             # 拉丁字符的平均推进宽度

# Java 里写的是 (int)(content_w * 0.42F)，float 字面量 0.42F 的实际值是：
FLOAT_042 = 0.41999998688697815


def text_w(s):
    """估算文字宽度，忽略 §x 颜色码。"""
    w = 0.0
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == '\u00a7' and i + 1 < len(s):       # § 颜色/格式码：零宽
            i += 2
            continue
        w += CJK if ord(ch) > 0x2E80 else ASCII
        i += 1
    return w


def jdiv(a, b):
    """Java 的整数除法：向零截断。"""
    return math.trunc(a / b)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def layout(width, height):
    content_w = min(width - 20, 520)
    left_x = jdiv(width, 2) - jdiv(content_w, 2)
    list_w = clamp(int(content_w * FLOAT_042), 124, 220)
    right_x = left_x + list_w + 10
    right_w = content_w - list_w - 10
    available = height - 78

    row_h, row_step = 20, 22
    needed = 16 + RIGHT_ROWS * row_step
    if needed > available:
        row_h, row_step = 18, 20
        needed = 16 + RIGHT_ROWS * row_step
    if needed > available:
        row_h, row_step = 16, 17
        needed = 16 + RIGHT_ROWS * row_step
    panel_h = min(needed, max(120, available))
    top_y = max(14, jdiv(height - (panel_h + 58), 2) + 10)

    panels = [("左", left_x, top_y, list_w, panel_h),
              ("右", right_x, top_y, right_w, panel_h)]

    # ---- 左侧：搜索框 + 列表可视区 ----
    search = (left_x + 3, top_y + 16, list_w - 6, SEARCH_H)
    list_top = top_y + 16 + SEARCH_H + 2
    list_bottom = top_y + panel_h - 3
    viewport = list_bottom - list_top
    visible_rows = viewport // ROW_H

    # ---- 右侧 ----
    sx = right_x + 4
    sw = right_w - 8
    half = jdiv(sw - 6, 2)
    y = top_y + 16
    widgets = []

    def add(name, x, yy, w, h, label):
        widgets.append((name, x, yy, w, h, label))

    add("生成开关", sx, y, sw, row_h, "§f生成开关：§a已启用§8（点击切换）")
    y += row_step
    add("生成数量", sx, y, sw, row_h, "生成数量：200")
    y += row_step
    add("生成规模", sx, y, sw, row_h, "生成规模：64")
    y += row_step
    add("生成权重", sx, y, sw, row_h, "生成权重(%)：100")
    y += row_step
    add("最低高度", sx, y, half, row_h, "最低高度：-128")
    add("最高高度", sx + half + 6, y, half, row_h, "最高高度：-128")
    y += row_step
    add("矿脉开关", sx, y, sw, row_h, "§f原版大型矿脉：§c已清除§8（点击）")
    y += row_step
    button_w = jdiv(sw - 8, 3)
    add("保存配置", sx, y, button_w, row_h, "保存配置")
    add("重新载入", sx + button_w + 4, y, button_w, row_h, "重新载入")
    add("恢复默认", sx + (button_w + 4) * 2, y, button_w, row_h, "恢复默认")
    last_row_bottom = y + row_h

    done_y = top_y + panel_h + 6
    done = ("完成", jdiv(width, 2) - 60, done_y, 120, 20)
    path_y = done_y + 24
    status_y = done_y + 36

    return dict(width=width, height=height, content_w=content_w, list_w=list_w,
                left_x=left_x, right_x=right_x, right_w=right_w, row_h=row_h,
                row_step=row_step, panel_h=panel_h, top_y=top_y, panels=panels,
                search=search, list_top=list_top, list_bottom=list_bottom,
                viewport=viewport, visible_rows=visible_rows, widgets=widgets,
                last_row_bottom=last_row_bottom, done=done, path_y=path_y,
                status_y=status_y, sw=sw, half=half, button_w=button_w)


def check(width, height):
    L = layout(width, height)
    problems = []
    W, H = width, height

    # 面板本身
    for name, x, y, w, h in L["panels"]:
        if x < 0 or y < 0 or x + w > W or y + h > H:
            problems.append(f"{name}面板超出屏幕 {x},{y},{w},{h}")

    (_, lx, ly, lw, lh), (_, rx, ry, rw, rh) = L["panels"]

    # 左右面板不重叠
    if lx + lw > rx:
        problems.append("左右面板重叠")

    # 搜索框：在左面板内、不与列表可视区重叠、宽度够用
    sx_, sy_, sw_, sh_ = L["search"]
    if sx_ < lx or sx_ + sw_ > lx + lw:
        problems.append(f"搜索框水平超界 {sx_}..{sx_ + sw_}")
    if sy_ < ly or sy_ + sh_ > L["list_top"]:
        problems.append(f"搜索框与列表区重叠 搜索框底={sy_ + sh_} 列表顶={L['list_top']}")
    if sw_ < 100:
        problems.append(f"搜索框过窄 {sw_}px（可用性不足）")

    # 列表可视区：在左面板内、不压到底边、至少 3 行
    if L["list_top"] < ly or L["list_bottom"] > ly + lh - 1:
        problems.append(f"列表可视区超出左面板 {L['list_top']}..{L['list_bottom']}")
    if L["viewport"] < ROW_H * 3:
        problems.append(f"列表可视区太矮 {L['viewport']}px（不足 3 行）")

    # 右侧控件
    for name, x, y, w, h, label in L["widgets"]:
        if x < rx or x + w > rx + rw:
            problems.append(f"{name} 水平超界 x={x} w={w} 面板={rx}..{rx + rw}")
        if y + h > ry + rh:
            problems.append(f"{name} 超出右面板底部 y+h={y + h} > {ry + rh}")
        tw = text_w(label)
        if tw > w:
            problems.append(f"{name} 文字溢出 {tw:.0f}px > 控件 {w}px")

    # 高度双滑块不重叠
    miny = [g for g in L["widgets"] if g[0] == "最低高度"][0]
    maxy = [g for g in L["widgets"] if g[0] == "最高高度"][0]
    if miny[1] + miny[3] > maxy[1]:
        problems.append("最低/最高高度滑块重叠")

    # 「完成」按钮 + 底部文字
    _, dx, dy, dw, dh = L["done"]
    if dx < 0 or dx + dw > W or dy + dh > H:
        problems.append(f"完成按钮超出屏幕 {dx},{dy},{dw},{dh}")
    if L["path_y"] + 9 > H:
        problems.append("底部配置路径提示超出屏幕")
    if L["status_y"] + 9 > H:
        problems.append("状态/提示文字超出屏幕")
    if dy < L["last_row_bottom"]:
        problems.append(f"完成按钮与右侧按钮行重叠 {dy} < {L['last_row_bottom']}")

    return L, problems


RESOLUTIONS = [
    (320, 240),   # MC 逻辑分辨率下限（最坏情况）
    (427, 240),   # 854x480 窗口 + GUI 缩放 2（开发客户端默认）
    (470, 270),
    (480, 270),
    (512, 288),
    (640, 360),
    (640, 480),
    (854, 480),
    (960, 540),
    (1280, 720),
]

DEFECTS = 0
print("=" * 104)
print("OreConfigScreen 布局验算（左侧可滚动列表版）")
print("=" * 104)
for w, h in RESOLUTIONS:
    L, problems = check(w, h)
    tag = "OK " if not problems else "FAIL"
    print(f"[{tag}] {w:>4}x{h:<4} 列表宽={L['list_w']:>3} 右面板宽={L['right_w']:>3} 面板高={L['panel_h']:>3} "
          f"行高={L['row_h']:>2} topY={L['top_y']:>3} 列表可视={L['viewport']:>3}px/{L['visible_rows']:>2}行 "
          f"右键行底={L['last_row_bottom']:>3} 完成Y={L['done'][2]:>3}")
    for p in problems:
        DEFECTS += 1
        print(f"        -> {p}")
print("-" * 104)
print(f"发现 {DEFECTS} 个问题")
print()

for w, h in [(320, 240), (427, 240)]:
    L, _ = check(w, h)
    print(f"### {w}x{h} 明细")
    print(f"  内容宽={L['content_w']} 左面板 x={L['left_x']} 宽={L['list_w']} | "
          f"右面板 x={L['right_x']} 宽={L['right_w']}")
    print(f"  搜索框 x={L['search'][0]}..{L['search'][0] + L['search'][2]} 高={L['search'][3]}")
    print(f"  列表可视区 y={L['list_top']}..{L['list_bottom']}（{L['viewport']}px，"
          f"整行可见 {L['visible_rows']} 行）")
    print(f"  右侧控件宽 sw={L['sw']} 半宽={L['half']} 三按钮各 {L['button_w']}px")
    for name, x, y, ww, hh, label in L["widgets"]:
        print(f"    {name:<10} x={x:>4}..{x + ww:<4} y={y:>3}..{y + hh:<3} "
              f"文字={text_w(label):>5.0f}px 余量={ww - text_w(label):>5.0f}px")
    print()
