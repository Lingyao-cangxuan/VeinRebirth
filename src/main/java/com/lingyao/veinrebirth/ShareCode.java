package com.lingyao.veinrebirth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

/**
 * 分享码：把整套矿物数值压成一段可以直接发给别人的短文本。
 * <p>
 * <b>格式</b>：{@code OGC2-<CRC32 十六进制 8 位>-<Base64URL 载荷>}。三段之间用 {@code -} 连接：
 * <ol>
 *     <li>{@code OGC2} 是格式标识（2 = 紧凑二进制版）；</li>
 *     <li>中间 8 位是载荷的 CRC32，用来在导入时判断码有没有被聊天框截断或复制时漏字；</li>
 *     <li>最后是载荷本身：<b>定长二进制</b>记录，Base64 URL-safe 编码并去掉填充符，
 *         所以结果里不含 {@code + / =}，粘进聊天、论坛、配置文本都不会被转义。</li>
 * </ol>
 * <p>
 * <b>载荷布局</b>（首字节是标志位；只有数值部分可能被压缩）：
 * <pre>
 * flags(1)           bit0 = 大型矿脉开关，bit1 = 数值区是否 deflate 压缩
 * n1(1)              原版矿物数量
 * mask(2, 小端)       原版矿物的启用位图
 * 每 7 字节 × n1       count(1) size(1) weight(1) minY(2) maxY(2)
 * n2(1)              接管的模组矿物数量
 * 每条：              idLen(1) + id(UTF-8) + 7 字节数值 + dim(1)
 * </pre>
 * <b>为什么这么写</b>：文本形式（{@code id=1,20,17,100,0,192} 一行一条）虽然可读，但十几个字符
 * 才表达一个字节的信息量，deflate 又对短文本没什么效果，最后 Base64 还要再膨胀三分之一 ——
 * 实测纯原版码要 <b>354 字符</b>。改成定长二进制后同样一份配置只要 <b>约 124 字符</b>，
 * 已经能塞进原版聊天框（上限 256），一串短消息就能发完。
 * <p>
 * <b>仍然无损</b>：原版 11 种矿物的开关与四个数值全部写入；接管（enabled = true）的模组矿物
 * 连 id 与维度一起写入。未接管的模组矿物不导出 —— 它们本就不参与生成，导入方也不会被改动，
 * 正好符合下面「只覆盖码里出现过的矿物」这条语义。
 * <p>
 * <b>向后兼容</b>：1.1.3 及更早版本的 {@code OGC1-} 文本码仍能正常导入（见 {@link #decodeLegacy}）。
 * <p>
 * <b>导入语义</b>：只覆盖码里出现过的矿物；分享者没装的模组矿物会保持导入者当前的值，
 * 不会被重置。码里出现但本机没识别到的矿物 id 会被跳过并计数（例如分享者装了通用机械、
 * 你没装），这样跨整合包分享不会出错。解析在临时结构上完成，全部通过后才写进运行时数值，
 * 所以码有问题时当前配置不会被改坏。
 */
public final class ShareCode {

    /** 当前格式标识。 */
    public static final String PREFIX = "OGC2";

    /** 1.1.3 及更早版本的前缀（纯文本 + deflate），导入端仍认。 */
    public static final String LEGACY_PREFIX = "OGC1";

    /** 导出文件：config/veinrebirth-share.txt */
    public static final String FILE_NAME = "veinrebirth-share.txt";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 标志位：大型矿脉开关。 */
    private static final int FLAG_VEINS = 0x01;
    /** 标志位：数值区经过 deflate 压缩（仅在真的更短时才用）。 */
    private static final int FLAG_PACKED = 0x02;

    /** 启用位图按 2 字节算，所以原版矿物最多 16 种（当前 11 种，留余量）。 */
    private static final int MAX_VANILLA = 16;

    /** 单条记录的固定字节数：count/size/weight 各 1 + minY/maxY 各 2。 */
    private static final int NUM_BYTES = 7;

    /** 解压时的单次缓冲大小。 */
    private static final int BUFFER = 2048;

    /** 防止畸形码把内存吃爆：载荷最多 64 KB，解压后的正文最多 256 KB。 */
    private static final int MAX_PAYLOAD = 64 * 1024;
    private static final int MAX_TEXT = 256 * 1024;

    /** 跳过项最多保留这么多条 id 用于提示，避免提示文字过长。 */
    private static final int SKIP_SAMPLE = 20;

    private ShareCode() {
    }

    /** 分享码文件路径：<游戏目录>/config/veinrebirth-share.txt */
    public static Path file() {
        return ConfigManager.file().resolveSibling(FILE_NAME);
    }

    // ================================================================== 导出

    /** 把当前数值编码成分享码。 */
    public static String encode() {
        return wrap(pack(encodeBody()));
    }

    /**
     * 生成可读的正文（调试、自检与人工排查用）。
     * <p>
     * 这不是分享码的传输格式（传输走 {@link #encode()} 的二进制载荷），但两者表达的
     * 是同一份快照，读这个比读 Base64 直观得多。
     */
    public static String serialize() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("veins=").append(ConfigManager.isVeinOresEnabled()).append('\n');
        for (OreType type : OreType.values()) {
            OreSettings settings = ConfigManager.get(type);
            sb.append(type.id()).append('=')
                    .append(settings.isEnabled() ? 1 : 0).append(',')
                    .append(settings.getCount()).append(',')
                    .append(settings.getSize()).append(',')
                    .append(settings.getWeight()).append(',')
                    .append(settings.getMinY()).append(',')
                    .append(settings.getMaxY());
            if (type.modded()) {
                OreGroup override = ConfigManager.dimensionOverride(type.id());
                if (override != null) {
                    sb.append(',').append(override.id());
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 组装载荷（首字节是标志位，bit1 先留 0，由 {@link #pack} 决定要不要压缩）。 */
    private static byte[] encodeBody() {
        List<OreType> vanilla = new ArrayList<>();
        List<OreType> modded = new ArrayList<>();
        for (OreType type : OreType.values()) {
            (type.modded() ? modded : vanilla).add(type);
        }

        int n1 = Math.min(vanilla.size(), MAX_VANILLA);
        int mask = 0;
        for (int i = 0; i < n1; i++) {
            if (ConfigManager.get(vanilla.get(i)).isEnabled()) {
                mask |= 1 << i;
            }
        }

        List<OreType> taken = new ArrayList<>();
        for (OreType type : modded) {
            if (ConfigManager.get(type).isEnabled()) {
                taken.add(type);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + n1 * NUM_BYTES + taken.size() * 24);
        out.write((ConfigManager.isVeinOresEnabled() ? FLAG_VEINS : 0) & 0xFF);
        out.write(n1 & 0xFF);
        out.write(mask & 0xFF);
        out.write((mask >>> 8) & 0xFF);
        for (int i = 0; i < n1; i++) {
            // 关掉的矿物也写数值：不生效时数值无意义，但写成「完整快照」语义最简单，
            // 导入后与原配置逐字段一致，不用去猜哪几个字节能省。
            writeNumbers(out, ConfigManager.get(vanilla.get(i)));
        }
        out.write(taken.size() & 0xFF);
        for (OreType type : taken) {
            byte[] id = type.id().getBytes(StandardCharsets.UTF_8);
            int len = Math.min(id.length, 255);
            out.write(len);
            out.write(id, 0, len);
            writeNumbers(out, ConfigManager.get(type));
            out.write(dimCode(ConfigManager.dimensionOverride(type.id())) & 0xFF);
        }
        return out.toByteArray();
    }

    private static void writeNumbers(ByteArrayOutputStream out, OreSettings settings) {
        out.write(clampByte(settings.getCount()));
        out.write(clampByte(settings.getSize()));
        out.write(clampByte(settings.getWeight()));
        writeShort(out, settings.getMinY());
        writeShort(out, settings.getMaxY());
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** 维度编码：0 = 无覆盖，否则是 {@link OreGroup} 序号 + 1（下限 1，所以不能用 ordinal 直接表示）。 */
    private static int dimCode(OreGroup group) {
        return group == null ? 0 : group.ordinal() + 1;
    }

    private static OreGroup dimFromCode(int code) {
        OreGroup[] groups = OreGroup.values();
        return code <= 0 || code > groups.length ? null : groups[code - 1];
    }

    /** 数值区太短，deflate 基本没有收益（还可能变长），所以只在真的更短时才压。 */
    private static byte[] pack(byte[] body) {
        if (body.length < 2) {
            return body;
        }
        byte[] zip = deflate(Arrays.copyOfRange(body, 1, body.length));
        if (zip.length + 1 >= body.length) {
            return body;
        }
        byte[] out = new byte[zip.length + 1];
        out[0] = (byte) ((body[0] | FLAG_PACKED) & 0xFF);
        System.arraycopy(zip, 0, out, 1, zip.length);
        return out;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buffer = new byte[BUFFER];
        try {
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                if (n == 0 && deflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /** Base64 + CRC32 + 前缀，组成最终分享码。 */
    static String wrap(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return PREFIX + "-" + String.format("%08x", crc.getValue()) + "-" + body;
    }

    // ================================================================== 导入

    /** 导入结果统计。 */
    public record Result(int applied, int skipped, List<String> skippedIds, Boolean veins) {

        /** 一句话摘要，命令与界面共用。 */
        public String summary() {
            StringBuilder sb = new StringBuilder("应用 ").append(this.applied).append(" 种矿物");
            if (this.skipped > 0) {
                sb.append("，跳过 ").append(this.skipped).append(" 种本机未识别");
                if (this.skippedIds.size() <= 3) {
                    sb.append("（").append(String.join("、", this.skippedIds)).append("）");
                }
            }
            if (this.veins != null) {
                sb.append("；大型矿脉").append(this.veins ? "保留" : "清除");
            }
            return sb.toString();
        }
    }

    /** 一条矿物设置（解码结果，与来源格式无关）。 */
    private record Entry(String id, boolean enabled, int count, int size, int weight,
                         int minY, int maxY, OreGroup dimension) {
    }

    /** 解码结果：条目列表 + 大型矿脉开关（null = 码里没带这项）。 */
    private record Decoded(List<Entry> entries, Boolean veins) {
    }

    /**
     * 导入分享码。解析在临时结构上完成，全部校验通过后才写进运行时数值，
     * 所以码有问题时当前配置不会被改坏。
     *
     * @throws IllegalArgumentException 码不合法（前缀不对、被截断、CRC 不符等），消息可直接展示给玩家
     */
    public static Result importCode(String code) {
        Decoded decoded = decode(code);

        List<String> skippedIds = new ArrayList<>();
        int skippedCount = 0;
        int applied = 0;
        List<Runnable> pending = new ArrayList<>();
        for (Entry entry : decoded.entries()) {
            OreType type = OreType.byId(entry.id());
            if (type == null) {
                if (skippedIds.size() < SKIP_SAMPLE) {
                    skippedIds.add(entry.id());
                }
                skippedCount++;
                continue;
            }
            pending.add(() -> {
                OreSettings settings = ConfigManager.get(type);
                settings.setEnabled(entry.enabled());
                // setXxx 内部会按各自的上下限 clamp，别人的极限值不会把本机搞出畸形状态
                settings.setCount(entry.count());
                settings.setSize(entry.size());
                settings.setWeight(entry.weight());
                settings.setMinY(entry.minY());
                settings.setMaxY(entry.maxY());
                if (entry.dimension() != null) {
                    ConfigManager.applyDimensionOverride(type.id(), entry.dimension());
                }
            });
            applied++;
        }

        if (applied == 0) {
            throw new IllegalArgumentException(skippedCount == 0
                    ? "码里没有任何矿物数据"
                    : "码里的矿物本机都没识别到（可能是别的整合包的矿物）");
        }

        pending.forEach(Runnable::run);
        if (decoded.veins() != null) {
            ConfigManager.setVeinOresEnabled(decoded.veins());
        }
        return new Result(applied, skippedCount, skippedIds, decoded.veins());
    }

    /** 清洗文本 → 认前缀 → 分发到新 / 旧两套解码。 */
    private static Decoded decode(String code) {
        // 先去掉颜色码/引号（保留换行），再去掉全部空白得到单行码
        String decorated = stripDecorations(code);
        if (decorated.isEmpty()) {
            throw new IllegalArgumentException("分享码是空的");
        }
        String compact = compact(decorated);
        String upper = compact.toUpperCase(Locale.ROOT);

        // 从聊天里整体复制时前面可能带着「分享码：」之类的前缀，找到真正的主体
        int modern = upper.indexOf(PREFIX + "-");
        if (modern >= 0) {
            return decodeBinary(compact.substring(modern));
        }
        int legacy = upper.indexOf(LEGACY_PREFIX + "-");
        if (legacy >= 0) {
            return decodeLegacy(compact.substring(legacy));
        }
        // 不是分享码格式；可能是有人直接把可读正文（以 veins= 开头）发出来了
        if (decorated.startsWith("veins=")) {
            return parseText(decorated);
        }
        throw new IllegalArgumentException("这不是本模组的分享码（应以 " + PREFIX + "- 开头）");
    }

    /** 解码 OGC2：校验 → Base64 →（可选）解压 → 解析二进制。 */
    private static Decoded decodeBinary(String code) {
        // 载荷是 URL-safe Base64，本身就含 '-'，所以限制最多切两刀，余下的整段留给载荷
        String[] parts = code.split("-", 3);
        if (parts.length < 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("分享码不完整（缺少校验段或内容段）");
        }
        long expected;
        try {
            expected = Long.parseLong(parts[1], 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分享码的校验段损坏");
        }

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分享码被截断了（Base64 段不完整）");
        }
        if (payload.length < 5 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("分享码内容异常");
        }

        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != expected) {
            throw new IllegalArgumentException("分享码校验失败，多半是复制时漏了字符");
        }

        return parseBinary(unpack(payload));
    }

    /** 按标志位决定要不要解压，解压后把 flags 里的压缩位清掉。 */
    private static byte[] unpack(byte[] payload) {
        int flags = payload[0] & 0xFF;
        if ((flags & FLAG_PACKED) == 0) {
            return payload;
        }
        byte[] plain = inflate(Arrays.copyOfRange(payload, 1, payload.length));
        if (plain.length == 0) {
            throw new IllegalArgumentException("分享码内容为空或已损坏");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 1);
        out.write(flags & ~FLAG_PACKED);
        out.write(plain, 0, plain.length);
        return out.toByteArray();
    }

    private static Decoded parseBinary(byte[] body) {
        List<OreType> vanilla = new ArrayList<>();
        for (OreType type : OreType.values()) {
            if (!type.modded()) {
                vanilla.add(type);
            }
        }

        List<Entry> entries = new ArrayList<>();
        int flags;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            flags = in.readUnsignedByte();
            int n1 = in.readUnsignedByte();
            int mask = in.readUnsignedByte() | (in.readUnsignedByte() << 8);

            for (int i = 0; i < n1; i++) {
                int count = in.readUnsignedByte();
                int size = in.readUnsignedByte();
                int weight = in.readUnsignedByte();
                int minY = in.readShort();
                int maxY = in.readShort();
                // 本机没有这个位置的矿物（版本差异）就丢掉，不影响其它条目
                if (i < vanilla.size()) {
                    OreType type = vanilla.get(i);
                    entries.add(new Entry(type.id(), (mask & (1 << i)) != 0,
                            count, size, weight, minY, maxY, null));
                }
            }

            int n2 = in.readUnsignedByte();
            for (int i = 0; i < n2; i++) {
                int len = in.readUnsignedByte();
                byte[] idBytes = new byte[len];
                in.readFully(idBytes);
                int count = in.readUnsignedByte();
                int size = in.readUnsignedByte();
                int weight = in.readUnsignedByte();
                int minY = in.readShort();
                int maxY = in.readShort();
                OreGroup dimension = dimFromCode(in.readUnsignedByte());
                entries.add(new Entry(new String(idBytes, StandardCharsets.UTF_8), true,
                        count, size, weight, minY, maxY, dimension));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("分享码内容已损坏");
        }
        return new Decoded(entries, (flags & FLAG_VEINS) != 0);
    }

    /** 解码 OGC1（1.1.3 及更早）：deflate + 文本正文。 */
    private static Decoded decodeLegacy(String code) {
        String[] parts = code.split("-", 3);
        if (parts.length < 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("分享码不完整（缺少校验段或内容段）");
        }
        long expected;
        try {
            expected = Long.parseLong(parts[1], 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分享码的校验段损坏");
        }
        byte[] packed;
        try {
            packed = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分享码被截断了（Base64 段不完整）");
        }
        byte[] raw = inflate(packed);
        CRC32 crc = new CRC32();
        crc.update(raw);
        if (crc.getValue() != expected) {
            throw new IllegalArgumentException("分享码校验失败，多半是复制时漏了字符");
        }
        return parseText(new String(raw, StandardCharsets.UTF_8));
    }

    /** 旧版文本正文：每行 {@code id=enabled,count,size,weight,minY,maxY[,维度]}，另有 {@code veins=}。 */
    private static Decoded parseText(String text) {
        List<Entry> entries = new ArrayList<>();
        Boolean veins = null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if ("veins".equalsIgnoreCase(key)) {
                veins = parseBool(value);
                continue;
            }
            String[] fields = value.split(",", -1);
            if (fields.length < 6) {
                continue;
            }
            try {
                entries.add(new Entry(key,
                        parseInt(fields[0]) != 0,
                        parseInt(fields[1]),
                        parseInt(fields[2]),
                        parseInt(fields[3]),
                        parseInt(fields[4]),
                        parseInt(fields[5]),
                        fields.length >= 7 && !fields[6].isBlank() ? OreGroup.byId(fields[6]) : null));
            } catch (NumberFormatException ignored) {
                // 这一条坏了不影响其它矿物
            }
        }
        return new Decoded(entries, veins);
    }

    private static byte[] inflate(byte[] packed) {
        Inflater inflater = new Inflater();
        inflater.setInput(packed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 4);
        byte[] buffer = new byte[BUFFER];
        try {
            int guard = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary() || inflater.finished()) {
                        break;
                    }
                    if (++guard > 64) {
                        break;
                    }
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_TEXT) {
                    throw new IllegalArgumentException("分享码内容异常");
                }
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("分享码内容已损坏");
        } finally {
            inflater.end();
        }
        if (out.size() == 0) {
            throw new IllegalArgumentException("分享码内容为空或已损坏");
        }
        return out.toByteArray();
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value.trim());
    }

    private static boolean parseBool(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    /**
     * 去掉 Minecraft 颜色 / 格式码（{@code §x}）和两端可能被带上的引号，其余原样保留。
     * <p>
     * 保留换行：旧版正文格式依赖它，而且只有这一步能分辨「码被换行折断了」和「发的就是正文」。
     */
    private static String stripDecorations(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\u00A7') {
                // 颜色 / 格式码：跳过 § 及其后一个字符
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 去掉全部空白。分享码本体不含空白，但从聊天或文件里复制常带上换行与空格。 */
    private static String compact(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u3000') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ================================================================== 文件

    /** 把分享码写进 config/veinrebirth-share.txt，方便整段发给别人。 */
    public static Path writeToFile(String code) throws Exception {
        Path path = file();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, code, StandardCharsets.UTF_8);
        return path;
    }

    /** 从 config/veinrebirth-share.txt 读取分享码。 */
    public static String readFromFile() throws Exception {
        Path path = file();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("没有找到 " + FILE_NAME + "，请先执行 /veinrebirth share");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 编码失败时的兜底日志（正常情况下不会走到）。 */
    static void logFailure(String what, Throwable t) {
        LOGGER.error("[VeinRebirth] {} failed", what, t);
    }
}
