# -*- coding: utf-8 -*-
"""
排查整合包内所有模组的「矿石 / 深层变体」配对情况。

目的：找出哪些模组矿物的**浅层方块没有可配对的深板岩变体**。
这类矿物被本模组接管后，旧版会把同一种方块同时挂到
stone_ore_replaceables 与 deepslate_ore_replaceables 上，
于是「深层矿区生成浅层矿」。

用法：
    python tools/audit_ore_variants.py <mods目录> [-o 报告.md]
"""
import io
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

# ---- 判定规则（与 OreDiscoverer 保持一致，另含拼音习惯） ----
ORE_SUFFIX = "_ore"
PINYIN_ORE_SUFFIX = "kuangshi"          # 矿石
DEEP_PREFIXES = ("deepslate_", "shenban", "shenceng")   # deepslate_ / 深层
DEEP_SUFFIX = "_deepslate_ore"
DEEP_HINT = re.compile(r"(^|_)deep($|_)|deepslate|shenban|shenceng")

# 明显不是矿物的黑名单（避免把 core / more 之类算进来）
SKIP = re.compile(r"(^|_)(core|more|store|score|shore)($|_)")

# ---- 维度判定关键词：与 OreDiscoverer 保持一致，否则报告会把下界/末地矿物算成误报 ----
NETHER_MATERIALS = ["nether", "quartz", "ancient", "soul", "blackstone", "basalt", "magma",
                    "blaze", "glowstone", "cobalt", "netherite", "wither", "brimstone", "cinder"]
END_MATERIALS = ["draconium", "chorus", "purpur", "shulker", "void", "awakened", "elytra"]


def classify_dimension(path, full_id, ground_netherrack, ground_end):
    """返回 'nether' / 'end' / 'overworld'，判定顺序与 OreDiscoverer.classify 完全一致。"""
    if full_id in ground_netherrack:
        return "nether"
    if full_id in ground_end:
        return "end"
    p = path.lower()
    if any(k in p for k in NETHER_MATERIALS):
        return "nether"
    if p.startswith("end") or "end_" in p or "ender" in p or any(k in p for k in END_MATERIALS):
        return "end"
    return "overworld"


def looks_like_ore(path):
    if SKIP.search(path):
        return False
    return path.endswith(ORE_SUFFIX) or path.endswith(PINYIN_ORE_SUFFIX)


def read_json(zf, name, limit=2 * 1024 * 1024):
    try:
        info = zf.getinfo(name)
        if info.file_size > limit:
            return None
        return json.loads(zf.read(name).decode("utf-8", "replace"))
    except Exception:
        return None


def scan_jar(jar, out=None, depth=0, tags=None):
    """返回 (namespace -> 数据) 的字典。会递归扫描 jar 套 jar（jarjar）。

    tags: 全局标签表 {(标签命名空间, 标签路径) -> {完整方块id}}。
    ⚠️ 关键：forge:ores 这类标签**注册在自己的命名空间下**（data/forge/tags/...），
    值却是别的命名空间的方块，所以绝不能按"标签所在命名空间 == 方块命名空间"来过滤。
    """
    if out is None:
        out = {}
    if tags is None:
        tags = {}
    try:
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()

            # 1) blockstates 目录 = 已注册方块的可靠代理
            blocks = defaultdict(set)
            for n in names:
                m = re.match(r"assets/([^/]+)/blockstates/(.+)\.json$", n)
                if m:
                    blocks[m.group(1)].add(m.group(2))

            # 2) 矿石方块
            for ns, paths in blocks.items():
                ores = sorted(p for p in paths if looks_like_ore(p))
                if not ores:
                    continue
                out.setdefault(ns, {
                    "jar": os.path.basename(jar),
                    "ores": ores,
                })

            # 3) forge 标签（全局收集，不按命名空间过滤）
            for n in names:
                m = re.match(r"data/([^/]+)/tags/blocks/(.+)\.json$", n)
                if not m:
                    continue
                data = read_json(zf, n)
                if not data:
                    continue
                vals = set()
                for v in data.get("values", []):
                    if isinstance(v, str):
                        vals.add(v.lstrip("#"))
                    elif isinstance(v, dict) and "id" in v:
                        vals.add(str(v["id"]).lstrip("#"))
                tags.setdefault((m.group(1), m.group(2)), set()).update(vals)

            # 4) jar 套 jar（Forge 的 jarjar）：继续往里扫
            if depth < 3:
                for n in names:
                    if n.endswith(".jar") and ("jarjar" in n or n.lower().endswith(".jar")):
                        try:
                            import tempfile
                            tmp = os.path.join(tempfile.gettempdir(),
                                               "audit_%d_%s" % (depth, os.path.basename(n)))
                            with io.open(tmp, "wb") as fh:
                                fh.write(zf.read(n))
                            scan_jar(tmp, out, depth + 1, tags)
                            os.remove(tmp)
                        except Exception:
                            pass
    except Exception as e:
        print("  ! 读取失败 %s: %s" % (os.path.basename(jar), e), file=sys.stderr)
    return out


def base_of(path):
    """深板岩变体 -> 主力方块名；不是变体返回 None。"""
    for pre in DEEP_PREFIXES:
        if path.startswith(pre):
            rest = path[len(pre):]
            if rest:
                return rest if rest.endswith(ORE_SUFFIX) or rest.endswith(PINYIN_ORE_SUFFIX) else None
    if path.endswith(DEEP_SUFFIX):
        return path[: -len(DEEP_SUFFIX)] + ORE_SUFFIX
    return None


def analyse(records, tags):
    ores_tag = tags.get(("forge", "ores"), set())
    g_stone = tags.get(("forge", "ores_in_ground/stone"), set())
    g_deep = tags.get(("forge", "ores_in_ground/deepslate"), set())
    g_nether = tags.get(("forge", "ores_in_ground/netherrack"), set())
    g_end = tags.get(("forge", "ores_in_ground/end_stone"), set())
    rows = []
    for ns, rec in sorted(records.items()):
        ores = set(rec["ores"])
        for p in sorted(ores):
            b = base_of(p)
            if b is not None and b in ores:
                continue                       # 这是变体，跳过
            partner = None
            for cand in ores:
                if base_of(cand) == p:
                    partner = cand
                    break
            full = "%s:%s" % (ns, p)
            rows.append({
                "ns": ns,
                "jar": rec["jar"],
                "ore": p,
                "partner": partner,
                "tagged_stone": full in g_stone,
                "tagged_deep": full in g_deep,
                "in_ores_tag": full in ores_tag,
                # 本模组旧版是否会把它当作候选：在 forge:ores 标签里，或命名以 _ore 结尾
                "discoverable": (full in ores_tag) or p.endswith(ORE_SUFFIX),
                "lone_is_deep": bool(DEEP_HINT.search(p)),
                # 维度：下界/末地矿物根本不受"浅层/深层"这套影响，必须排除
                "dim": classify_dimension(p, full, g_nether, g_end),
            })
    return rows


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    args = sys.argv[1:]
    out_path = None
    if "-o" in args:
        i = args.index("-o")
        out_path = args[i + 1]
        args = args[:i] + args[i + 2:]
    mods_dirs = args

    jars = []
    for d in mods_dirs:
        jars += [os.path.join(d, f) for f in sorted(os.listdir(d))
                 if f.lower().endswith(".jar") and not f.lower().endswith(".disabled")]
    print("扫描 %d 个 jar（%d 个目录）..." % (len(jars), len(mods_dirs)))

    all_records = {}
    tags = {}
    for j in jars:
        scan_jar(j, all_records, 0, tags)

    rows = analyse(all_records, tags)
    ow = [r for r in rows if r["dim"] == "overworld"]     # 只有主世界矿物受本 bug 影响

    # A1 = 会被识别 + 无配对变体 + 未自报宿主岩石 → 旧版必现"深层出浅层矿"
    a1 = [r for r in ow if r["partner"] is None and not r["lone_is_deep"]
          and not r["tagged_deep"] and not r["tagged_stone"] and r["discoverable"]]
    # A2 = 同类，但旧版本模组识别不到（命名不符合 _ore 且没打标签）
    a2 = [r for r in ow if r["partner"] is None and not r["lone_is_deep"]
          and not r["tagged_deep"] and not r["tagged_stone"] and not r["discoverable"]]
    # B = 只有深层形态 → 旧版会"浅层出深层矿"
    b = [r for r in ow if r["partner"] is None and r["lone_is_deep"]
         and not r["tagged_stone"] and r["discoverable"]]
    ok = [r for r in ow if r["partner"]]
    safe_tag = [r for r in ow if r["partner"] is None and (r["tagged_deep"] or r["tagged_stone"])]
    other_dim = [r for r in rows if r["dim"] != "overworld"]

    lines = []
    lines.append("# 整合包矿石配对排查报告\n")
    lines.append("扫描 jar：**%d** 个（%d 个目录）｜ 含矿石的命名空间：**%d** 个 ｜ "
                 "矿石条目：**%d** 条（其中主世界 **%d** 条）\n"
                 % (len(jars), len(mods_dirs), len(all_records), len(rows), len(ow)))

    lines.append("\n## A1. 会被本模组识别、且缺深板岩变体 —— 旧版必现「深层矿区生成浅层矿」（%d 条）\n"
                 % len(a1))
    if a1:
        lines.append("| 命名空间 | 浅层方块 | 在 forge:ores | 所属 jar |")
        lines.append("|---|---|---|---|")
        for r in a1:
            lines.append("| %s | %s | %s | %s |"
                         % (r["ns"], r["ore"], "是" if r["in_ores_tag"] else "否", r["jar"]))
    else:
        lines.append("（无）")

    lines.append("\n## A2. 同类问题，但旧版识别不到它（命名不以 _ore 结尾且未打标签，%d 条）\n" % len(a2))
    if a2:
        lines.append("| 命名空间 | 方块 | 说明 |")
        lines.append("|---|---|---|")
        for r in a2:
            lines.append("| %s | %s | 拼音 id，旧版不扫描 |" % (r["ns"], r["ore"]))
    else:
        lines.append("（无）")

    lines.append("\n## B. 只有深层形态 —— 旧版会把深层矿塞进浅层（%d 条）\n" % len(b))
    if b:
        lines.append("| 命名空间 | 方块 |")
        lines.append("|---|---|")
        for r in b:
            lines.append("| %s | %s |" % (r["ns"], r["ore"]))
    else:
        lines.append("（无）")

    lines.append("\n## C. 有配对变体 —— 不受影响（%d 条）\n" % len(ok))
    by_ns = defaultdict(list)
    for r in ok:
        by_ns[r["ns"]].append("%s ↔ %s" % (r["ore"], r["partner"]))
    lines.append("| 命名空间 | 配对数 | 示例 |")
    lines.append("|---|---|---|")
    for ns, items in sorted(by_ns.items()):
        lines.append("| %s | %d | %s |" % (ns, len(items), items[0]))

    lines.append("\n## D. 靠 ores_in_ground 标签自报宿主岩石（%d 条）\n" % len(safe_tag))
    if safe_tag:
        lines.append("| 命名空间 | 方块 | 标签 |")
        lines.append("|---|---|---|")
        for r in safe_tag:
            tag = []
            if r["tagged_stone"]:
                tag.append("stone")
            if r["tagged_deep"]:
                tag.append("deepslate")
            lines.append("| %s | %s | %s |" % (r["ns"], r["ore"], "+".join(tag)))
    else:
        lines.append("（无 —— 没有模组声明 `ores_in_ground` 标签，说明**不能指望模组自报宿主岩石**，"
                     "必须靠命名/标签启发式兜底）")

    lines.append("\n## E. 下界 / 末地矿物 —— 走的是另一套替换规则，不受本 bug 影响（%d 条）\n"
                 % len(other_dim))
    if other_dim:
        lines.append("| 命名空间 | 方块 | 维度 |")
        lines.append("|---|---|---|")
        for r in other_dim:
            lines.append("| %s | %s | %s |" % (r["ns"], r["ore"], r["dim"]))

    report = "\n".join(lines)
    print(report)
    if out_path:
        with io.open(out_path, "w", encoding="utf-8") as f:
            f.write(report)
        print("\n报告已写入 %s" % out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
