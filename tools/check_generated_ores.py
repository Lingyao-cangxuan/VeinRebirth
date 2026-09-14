"""
直接解析 Minecraft 1.20.1 区域文件（.mca），统计已生成区块里的矿物方块数量与高度分布。
用于验证 VeinRebirth 模组的矿物是否按配置真正生成。

重要：只统计 Status = minecraft:full 的区块。
半成品区块（structure_starts / biomes / carvers / initialize_light）的地形与矿脉已经写入，
但**特征阶段还没跑**，也就是说原版矿石簇和本模组的清理都还没执行——
把它们算进来会得到"矿物没被清掉"的假结论（本模组调试时踩过这个坑）。

用法：python check_generated_ores.py <世界目录> [--chunk-limit N] [--all-status]
"""
import collections
import struct
import sys
import zlib
from pathlib import Path

FULL_STATUS = "minecraft:full"

ORE_NAMES = {
    "coal_ore": "煤炭",
    "copper_ore": "铜",
    "iron_ore": "铁",
    "gold_ore": "金",
    "redstone_ore": "红石",
    "diamond_ore": "钻石",
    "lapis_ore": "青金石",
    "emerald_ore": "绿宝石",
    "nether_quartz_ore": "下界石英",
    "nether_gold_ore": "下界金",
    "ancient_debris": "远古残骸",
}

# 原版大型矿脉的特征方块，用于确认残留是否来自矿脉
VEIN_HINTS = {
    "raw_copper_block": "粗铜块（矿脉）",
    "raw_iron_block": "粗铁块（矿脉）",
    "granite": "花岗岩（铜矿脉填充）",
    "tuff": "凝灰岩（铁矿脉填充）",
}

# ---------------------------------------------------------------- NBT 解析

def _read_payload(buf, off, tag):
    if tag == 1:
        value = buf[off]
        if value > 127:  # NBT 的 Byte 是有符号的
            value -= 256
        return value, off + 1
    if tag == 2:
        return struct.unpack_from(">h", buf, off)[0], off + 2
    if tag == 3:
        return struct.unpack_from(">i", buf, off)[0], off + 4
    if tag == 4:
        return struct.unpack_from(">q", buf, off)[0], off + 8
    if tag == 5:
        return struct.unpack_from(">f", buf, off)[0], off + 4
    if tag == 6:
        return struct.unpack_from(">d", buf, off)[0], off + 8
    if tag == 7:  # byte array
        n = struct.unpack_from(">i", buf, off)[0]
        return buf[off + 4:off + 4 + n], off + 4 + n
    if tag == 8:  # string
        n = struct.unpack_from(">H", buf, off)[0]
        return buf[off + 2:off + 2 + n].decode("utf-8", "replace"), off + 2 + n
    if tag == 9:  # list
        item, n = struct.unpack_from(">bi", buf, off)
        off += 5
        out = []
        for _ in range(n):
            val, off = _read_payload(buf, off, item)
            out.append(val)
        return out, off
    if tag == 10:  # compound
        out = {}
        while True:
            t = buf[off]
            off += 1
            if t == 0:
                return out, off
            n = struct.unpack_from(">H", buf, off)[0]
            off += 2
            name = buf[off:off + n].decode("utf-8", "replace")
            off += n
            val, off = _read_payload(buf, off, t)
            out[name] = val
    if tag == 11:  # int array
        n = struct.unpack_from(">i", buf, off)[0]
        return list(struct.unpack_from(">%di" % n, buf, off + 4)), off + 4 + 4 * n
    if tag == 12:  # long array
        n = struct.unpack_from(">i", buf, off)[0]
        return list(struct.unpack_from(">%dq" % n, buf, off + 4)), off + 4 + 8 * n
    raise ValueError("未知 NBT 标签类型 %r" % tag)


def parse_nbt(buf):
    if buf[0] != 10:
        raise ValueError("根标签不是 Compound")
    n = struct.unpack_from(">H", buf, 1)[0]
    off = 3 + n
    return _read_payload(buf, off, 10)[0]


# ---------------------------------------------------------------- 区域文件

def iter_chunk_nbt(path):
    with open(path, "rb") as f:
        header = f.read(8192)
        if len(header) < 8192:
            return
        for i in range(1024):
            off = int.from_bytes(header[i * 4:i * 4 + 3], "big")
            cnt = header[i * 4 + 3]
            if off == 0 or cnt == 0:
                continue
            f.seek(off * 4096)
            raw_len = f.read(4)
            if len(raw_len) < 4:
                continue
            length = int.from_bytes(raw_len, "big")
            comp = f.read(1)[0]
            payload = f.read(length - 1)
            try:
                if comp == 2:
                    data = zlib.decompress(payload)
                elif comp == 1:
                    import gzip
                    data = gzip.decompress(payload)
                else:
                    data = payload
            except Exception:
                continue
            yield i, data


def decode_section(section):
    """返回 {方块名: 数量}"""
    states = section.get("block_states")
    if not states:
        return {}
    palette = [p.get("Name", "?") for p in states.get("palette", [])]
    if not palette:
        return {}
    if len(palette) == 1 or "data" not in states:
        return {palette[0]: 4096}
    longs = states["data"]
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    counts = collections.Counter()
    for i in range(4096):
        li, o = divmod(i, per_long)
        if li >= len(longs):
            break
        idx = (longs[li] >> (o * bits)) & mask
        if idx < len(palette):
            counts[palette[idx]] += 1
    return dict(counts)


def section_biomes(section):
    """返回该段（16x16x16）涉及的生物群系名集合。"""
    biomes = section.get("biomes")
    if not biomes:
        return set()
    palette = biomes.get("palette", [])
    return {str(b) for b in palette}


# ---------------------------------------------------------------- 主流程

def main():
    world = Path(sys.argv[1] if len(sys.argv) > 1 else "run/smoketest")
    limit = None
    if "--chunk-limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--chunk-limit") + 1])
    all_status = "--all-status" in sys.argv

    region_dir = world / "region"
    if not region_dir.is_dir():
        print("找不到区域目录：%s" % region_dir)
        return 1

    status_count = collections.Counter()
    skipped = 0

    totals = collections.Counter()
    y_range = {}
    palette_hits = collections.Counter()
    biome_hits = collections.defaultdict(collections.Counter)
    vein_hits = collections.Counter()
    chunks = 0

    for region in sorted(region_dir.glob("r.*.mca")):
        for _idx, data in iter_chunk_nbt(region):
            if limit is not None and chunks >= limit:
                break
            try:
                chunk = parse_nbt(data)
            except Exception as e:
                print("解析区块失败：%s" % e)
                continue
            status = str(chunk.get("Status", "?"))
            status_count[status] += 1
            if not all_status and status != FULL_STATUS:
                # 半成品区块：地形已有但特征没跑，不计入统计
                skipped += 1
                continue
            chunks += 1
            for section in chunk.get("sections", []):
                counts = decode_section(section)
                if not counts:
                    continue
                sy = section.get("Y", 0)
                ores_here = {k.replace("minecraft:", "").replace("deepslate_", "")
                             for k in counts if k.replace("minecraft:", "").replace("deepslate_", "") in ORE_NAMES}
                for name, count in counts.items():
                    short = name.replace("minecraft:", "")
                    if short in VEIN_HINTS:
                        vein_hits[short] += count
                if not ores_here:
                    continue
                biomes = section_biomes(section) or {"<未知>"}
                for name, count in counts.items():
                    short = name.replace("minecraft:", "")
                    base = short.replace("deepslate_", "")
                    if base not in ORE_NAMES:
                        continue
                    totals[base] += count
                    palette_hits[base] += 1
                    lo, hi = y_range.get(base, (9999, -9999))
                    y_range[base] = (min(lo, sy * 16), max(hi, sy * 16 + 15))
                    for b in biomes:
                        biome_hits[base][b] += count

    print("区块状态分布：")
    for status, n in sorted(status_count.items()):
        mark = "  <- 计入统计" if (all_status or status == FULL_STATUS) else ""
        print("   %-30s %d 个%s" % (status, n, mark))
    print()
    if skipped:
        print("已跳过 %d 个半成品区块（特征阶段未执行，不参与统计）。" % skipped)
        print("需要统计全部区块时加 --all-status。")
        print()
    print("参与统计的完整区块数：%d" % chunks)
    print()
    print("原版大型矿脉标志物：")
    for key, label in VEIN_HINTS.items():
        n = vein_hits.get(key, 0)
        flag = "  <-- 矿脉已被清除" if n == 0 and key.startswith("raw") else ""
        print("   %-20s %8d  %s%s" % (key, n, label, flag))
    print()
    if not totals:
        print("没有发现任何矿物。")
        return 0
    print("%-8s %-18s %10s %8s   %s" % ("矿物", "id", "方块总数", "出现段数", "实际高度范围"))
    for base, name in ORE_NAMES.items():
        if totals[base] == 0:
            print("%-8s %-18s %10s %8d   %s" % (name, base, "-", 0, "（未生成）"))
            continue
        lo, hi = y_range[base]
        print("%-8s %-18s %10d %8d   Y %d ~ %d" % (name, base, totals[base], palette_hits[base], lo, hi))
        top = biome_hits[base].most_common(4)
        if top:
            print("          来源生物群系：%s" % "，".join("%s(%d)" % (b.replace("minecraft:", ""), c) for b, c in top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
