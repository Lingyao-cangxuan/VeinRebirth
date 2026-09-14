"""
统计残留矿物分布在多少个区块里，并列出区块坐标，
用来判断"清理功能没跑到这些区块"还是"清理跑了但被覆盖"。

用法：python residual_chunks.py <世界目录>
"""
import collections
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from check_generated_ores import iter_chunk_nbt, parse_nbt, decode_section

TARGETS = {"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
           "minecraft:iron_ore", "minecraft:deepslate_iron_ore"}


def main():
    world = Path(sys.argv[1] if len(sys.argv) > 1 else "run/smoketest")

    total_chunks = 0
    chunks_with_ore = set()
    per_chunk = collections.Counter()
    ore_in_chunk = collections.defaultdict(collections.Counter)
    vein_shape_chunks = set()

    for region in sorted((world / "region").glob("r.*.mca")):
        for _idx, data in iter_chunk_nbt(region):
            total_chunks += 1
            try:
                chunk = parse_nbt(data)
            except Exception:
                continue
            cx, cz = chunk.get("xPos"), chunk.get("zPos")
            for section in chunk.get("sections", []):
                counts = decode_section(section)
                if not counts:
                    continue
                got = {k: v for k, v in counts.items() if k in TARGETS}
                if not got:
                    continue
                chunks_with_ore.add((cx, cz))
                for k, v in got.items():
                    per_chunk[(cx, cz)] += v
                    ore_in_chunk[(cx, cz)][k.replace("minecraft:", "")] += v
                # 是否带矿脉特征填充（tuff / granite / raw_*_block）
                if any(h in counts for h in ["minecraft:tuff", "minecraft:granite",
                                             "minecraft:raw_copper_block", "minecraft:raw_iron_block"]):
                    vein_shape_chunks.add((cx, cz))

    print("总区块数：%d" % total_chunks)
    print("含残留矿的区块数：%d（占 %.1f%%）" % (len(chunks_with_ore),
                                        100.0 * len(chunks_with_ore) / max(1, total_chunks)))
    print("其中带矿脉特征填充的区块：%d" % len(vein_shape_chunks))
    print()
    print("残留最多的 20 个区块：")
    for (cx, cz), n in per_chunk.most_common(20):
        detail = dict(ore_in_chunk[(cx, cz)])
        print("   (%4d, %4d)  合计 %4d  %s" % (cx, cz, n, detail))
    print()
    xs = sorted({c[0] for c in chunks_with_ore})
    zs = sorted({c[1] for c in chunks_with_ore})
    print("残留区块 X 范围：%s ~ %s" % (xs[0] if xs else "-", xs[-1] if xs else "-"))
    print("残留区块 Z 范围：%s ~ %s" % (zs[0] if zs else "-", zs[-1] if zs else "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
