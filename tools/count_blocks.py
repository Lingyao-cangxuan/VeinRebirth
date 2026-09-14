# -*- coding: utf-8 -*-
"""
在存档的区域文件里统计任意方块（默认只看 Status = minecraft:full 的区块）。

给"接管生成"验证用：测试矿物 testmod:tin_ore 没有任何世界生成，所以世界里出现的
每一块都必然来自 VeinRebirth 的特征。统计它存在与否、数量与高度范围，就能证明
「识别 → 接管 → 按配置生成」这条链路真的通了。

用法：
    python count_blocks.py <世界目录> <方块id前缀> [<方块id前缀> ...]
    python count_blocks.py run/smoketest testmod:
    python count_blocks.py run/smoketest testmod:tin_ore --all-status
"""
import collections
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from check_generated_ores import iter_chunk_nbt, parse_nbt, decode_section

FULL_STATUS = "minecraft:full"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    world = Path(sys.argv[1])
    args = [a for a in sys.argv[2:] if not a.startswith("--")]
    all_status = "--all-status" in sys.argv

    region_dir = world / "region"
    if not region_dir.is_dir():
        print("找不到区域目录：%s" % region_dir)
        return 1

    status_count = collections.Counter()
    totals = collections.Counter()
    y_range = {}
    chunk_hits = collections.Counter()
    chunks = 0
    skipped = 0

    for region in sorted(region_dir.glob("r.*.mca")):
        for _idx, data in iter_chunk_nbt(region):
            try:
                chunk = parse_nbt(data)
            except Exception:
                continue
            status = str(chunk.get("Status", "?"))
            status_count[status] += 1
            if not all_status and status != FULL_STATUS:
                skipped += 1
                continue
            chunks += 1
            for section in chunk.get("sections", []):
                counts = decode_section(section)
                if not counts:
                    continue
                sy = section.get("Y", 0)
                for name, count in counts.items():
                    if not any(name.startswith(p) for p in args):
                        continue
                    totals[name] += count
                    chunk_hits[name] += 1
                    lo, hi = y_range.get(name, (9999, -9999))
                    y_range[name] = (min(lo, sy * 16), max(hi, sy * 16 + 15))

    print("区块状态分布：")
    for status, n in sorted(status_count.items()):
        mark = "  <- 计入统计" if (all_status or status == FULL_STATUS) else ""
        print("   %-30s %d 个%s" % (status, n, mark))
    if skipped:
        print("已跳过 %d 个半成品区块（特征阶段未执行）" % skipped)
    print("参与统计的完整区块数：%d" % chunks)
    print()
    if not totals:
        print("没有找到匹配 %s 的方块。" % args)
        return 0
    print("%-34s %10s %10s   %s" % ("方块", "总数", "出现段数", "实际高度范围"))
    for name in sorted(totals):
        lo, hi = y_range[name]
        print("%-34s %10d %10d   Y %d ~ %d" % (name, totals[name], chunk_hits[name], lo, hi))
    return 0


if __name__ == "__main__":
    sys.exit(main())
