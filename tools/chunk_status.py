"""
检查区块的 NBT Status 字段，判断残留矿区块是不是"只生成到半途、还没跑特征"。

用法：python chunk_status.py <世界目录> <服务端日志>
"""
import collections
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from check_generated_ores import iter_chunk_nbt, parse_nbt, decode_section

TARGETS = {"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
           "minecraft:iron_ore", "minecraft:deepslate_iron_ore"}


def main():
    world = Path(sys.argv[1] if len(sys.argv) > 1 else "run/smoketest")
    log = Path(sys.argv[2] if len(sys.argv) > 2 else "run_server7.log")

    calls = set()
    if log.exists():
        for line in log.open(encoding="utf-8", errors="replace"):
            m = re.search(r"VEINDIAG[23] chunk=\((-?\d+),(-?\d+)\)", line)
            if m:
                calls.add((int(m.group(1)), int(m.group(2))))

    by_group = collections.Counter()
    residual = collections.Counter()
    residual_total = 0

    for region in sorted((world / "region").glob("r.*.mca")):
        for _i, data in iter_chunk_nbt(region):
            try:
                c = parse_nbt(data)
            except Exception:
                continue
            pos = (c.get("xPos"), c.get("zPos"))
            st = str(c.get("Status", "?"))
            called = pos in calls
            has_ore = False
            for sec in c.get("sections", []):
                cnt = decode_section(sec)
                if any(k in TARGETS for k in cnt):
                    has_ore = True
                    break
            grp = "已跑特征" if called else "未跑特征"
            by_group[(grp, st)] += 1
            if has_ore:
                residual_total += 1
                residual[(grp, st)] += 1

    print("=== 区块状态分布（按是否跑过本模组特征分组） ===")
    for (grp, st), n in sorted(by_group.items()):
        print("  %-8s  %-28s  %d 个" % (grp, st, n))
    print()
    print("=== 含残留矿的区块（共 %d 个）状态分布 ===" % residual_total)
    for (grp, st), n in sorted(residual.items()):
        print("  %-8s  %-28s  %d 个" % (grp, st, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
