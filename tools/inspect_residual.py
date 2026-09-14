"""
诊断残留矿物：把含铜/铁矿的段整个调色板打出来，看是否带有原版大型矿脉的特征
（铜矿脉 = copper_ore + raw_copper_block + granite；铁矿脉 = iron_ore + raw_iron_block + tuff）

用法：python inspect_residual.py <世界目录>
"""
import collections
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from check_generated_ores import iter_chunk_nbt, parse_nbt, decode_section, section_biomes

TARGETS = {"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
           "minecraft:iron_ore", "minecraft:deepslate_iron_ore"}
VEIN_HINTS = ["raw_copper_block", "raw_iron_block", "granite", "tuff", "diorite", "andesite"]


def main():
    world = Path(sys.argv[1] if len(sys.argv) > 1 else "run/smoketest")
    region_dir = world / "region"

    all_blocks = collections.Counter()
    sections_with_ore = []
    hint_totals = collections.Counter()

    for region in sorted(region_dir.glob("r.*.mca")):
        for idx, data in iter_chunk_nbt(region):
            try:
                chunk = parse_nbt(data)
            except Exception:
                continue
            cpos = (chunk.get("x"), chunk.get("z"))
            for section in chunk.get("sections", []):
                counts = decode_section(section)
                if not counts:
                    continue
                for k, v in counts.items():
                    name = k.replace("minecraft:", "")
                    all_blocks[name] += v
                    if name in [h for h in VEIN_HINTS]:
                        hint_totals[name] += v
                if any(k in TARGETS for k in counts):
                    sections_with_ore.append((cpos, section.get("Y", 0), counts,
                                              section_biomes(section)))

    print("=== 全世界的相关方块总量 ===")
    for name in ["copper_ore", "deepslate_copper_ore", "iron_ore", "deepslate_iron_ore",
                 "raw_copper_block", "raw_iron_block", "granite", "tuff", "diorite", "andesite",
                 "stone", "deepslate"]:
        print("   %-22s %d" % (name, all_blocks.get(name, 0)))

    print()
    print("=== 含铜/铁矿的段数量：%d ===" % len(sections_with_ore))
    print("    其中调色板里同时出现矿脉特征方块的段：%d" % sum(
        1 for _c, _y, cnt, _b in sections_with_ore
        if any(h in cnt for h in ["minecraft:raw_copper_block", "minecraft:raw_iron_block",
                                   "minecraft:granite", "minecraft:tuff"])))

    print()
    print("=== 前 12 个含矿段详情 ===")
    for cpos, sy, counts, biomes in sections_with_ore[:12]:
        shown = {k.replace("minecraft:", ""): v for k, v in
                 sorted(counts.items(), key=lambda kv: -kv[1])[:12]}
        print("  区块%s Y段=%d  群系=%s" % (cpos, sy, sorted(b.replace("minecraft:", "") for b in biomes)))
        print("      %s" % shown)

    print()
    print("=== 每个含矿段是否带矿脉特征 ===")
    with_hint = 0
    without = 0
    for cpos, sy, counts, biomes in sections_with_ore:
        has = [h.replace("minecraft:", "") for h in
               ["minecraft:raw_copper_block", "minecraft:raw_iron_block",
                "minecraft:granite", "minecraft:tuff"] if h in counts]
        if has:
            with_hint += 1
        else:
            without += 1
    print("  有矿脉特征填充：%d 段" % with_hint)
    print("  无矿脉特征填充：%d 段" % without)
    return 0


if __name__ == "__main__":
    sys.exit(main())
