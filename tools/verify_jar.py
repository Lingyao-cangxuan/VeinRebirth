"""校验构建产物的 jar：占位符展开、srg 重映射、数据包完整性。

用法（在工程根目录执行）：
    python tools/verify_jar.py                       # 自动取 build/libs 下最新的产物
    python tools/verify_jar.py build/libs/veinrebirth-1.2.1.jar
"""
import glob
import os
import re
import sys
import zipfile

if len(sys.argv) > 1:
    jar = sys.argv[1]
else:
    cands = [p for p in glob.glob("build/libs/veinrebirth-*.jar")
             if not re.search(r"-(sources|javadoc|dev|slim)\.jar$", p)]
    cands.sort(key=os.path.getmtime, reverse=True)
    jar = cands[0] if cands else "build/libs/veinrebirth-1.2.1.jar"
z = zipfile.ZipFile(jar)
names = z.namelist()
print("jar: %s" % jar)
print("条目数: %d" % len(names))
print()

print("=== mods.toml（检查占位符是否展开 + 中文） ===")
m = z.read("META-INF/mods.toml").decode("utf-8")
for ln in m.split("\n")[:24]:
    print("   ", ln)
print()
placeholder = "$" + "{"
print("是否残留未展开占位符:", ("是——有问题！" if placeholder in m else "否（已全部展开）"))
print()

print("=== 关键 class（检查 srg 重映射） ===")
for cls in ["com/lingyao/veinrebirth/OreVeins.class",
            "com/lingyao/veinrebirth/ConfigOreFeature.class",
            "com/lingyao/veinrebirth/ChunkRefresher.class",
            "com/lingyao/veinrebirth/client/OreConfigScreen.class"]:
    try:
        data = z.read(cls)
    except KeyError:
        print("   %-48s  <缺失！>" % cls)
        continue
    srg = len(re.findall(rb"m_\d+_|f_\d+_", data))
    flag = "" if srg > 0 else "  <-- 没有 srg 引用，可能没重映射！"
    print("   %-48s %6d 字节  srg 引用 %d 处%s" % (cls, len(data), srg, flag))
print()

print("=== 数据包文件 ===")
for n in sorted(names):
    if n.startswith("data/"):
        print("   ", n)
print()

print("=== 语言/资源 ===")
for n in sorted(names):
    if n.startswith("META-INF") or n.endswith(".mcmeta"):
        print("   ", n)
