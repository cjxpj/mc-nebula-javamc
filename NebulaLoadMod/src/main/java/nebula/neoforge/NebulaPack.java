package nebula.neoforge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 资源包投送：收集各词库插件 {@code <gameDir>/nebula/<插件>/资源/} 打成 zip，用内置 HTTP 服务发给客户端。
 *
 * <p>客户端不装任何 mod —— 走的是原版资源包机制：进服收到提示，点「接受」后永久缓存，
 * 之后自定义物品模型、生物贴图、音效、界面贴图全都由这份资源包提供。
 *
 * <p>没有共享底板目录：{@code pack.mcmeta} 由本类内建，资源全部跟插件走。
 * 一个插件的 {@code 资源/} 都没有时整套功能静默关闭，服务器照常运行（纯原版外观）。
 */
public final class NebulaPack {
    private static final Logger LOG = LogManager.getLogger("nebula");

    /** 配置文件名，放在 config/nebula-bridge/ 下。 */
    private static final String CONFIG_FILE = "pack.properties";
    /**
     * 内建的 pack.mcmeta：1.21.9+ 的 pack.mcmeta 在 resource 主版本 >64 时必须给
     * min_format / max_format，否则客户端整个包拒收。1.21.11 = [75, 0]。
     * 内容与去掉底板前磁盘上那份逐字节一致，因此资源包 sha1 不变、客户端不用重下。
     */
    private static final byte[] PACK_META = ("{\n  \"pack\": {\n"
            + "    \"description\": \"§b星云§r 自定义物品与生物外观\",\n"
            + "    \"min_format\": [75, 0],\n"
            + "    \"max_format\": [75, 0]\n  }\n}\n")
            .getBytes(StandardCharsets.UTF_8);

    private static volatile byte[] zip;
    private static volatile String sha1 = "";
    private static volatile String baseUrl = "";
    /** pack.properties 里显式写的 host（含端口原样）；空 = 由 guessHost 猜。 */
    private static volatile String configuredHost = "";
    private static volatile boolean required = false;
    private static volatile boolean running = false;
    private static ServerSocket socket;
    private static Thread listener;

    private NebulaPack() {
    }

    // ────────────────────────────── 生命周期 ──────────────────────────────

    /**
     * 开服时调用：打包 + 起 HTTP 服务。返回一句给日志用的人类可读摘要，
     * 目录不存在时返回 null（表示未启用）。
     */
    static String start(MinecraftServer server) {
        stop();
        Path pluginRoot = FMLPaths.GAMEDIR.get().resolve("nebula");

        byte[] built = null;
        String packNote = "";
        if (!Files.isDirectory(pluginRoot)) {
            packNote = "未找到 nebula/ 词库目录";
        } else {
            try {
                built = buildZip(pluginRoot);
                if (built == null) {
                    packNote = "没有插件资源（缺 资源/ 与 创造物品.json）";
                }
            } catch (Exception e) {
                LOG.warn("[星云] 资源包打包失败（跳过投送）：{}", e.toString());
                packNote = "资源包打包失败";
            }
        }

        // mod 自发布：即使没有资源包也要起来，客户端靠它检查版本。
        String modNote = NebulaModDistributor.prepare();

        boolean hasPack = built != null && built.length > 0;
        if (!hasPack && !NebulaModDistributor.available()) {
            return null;
        }
        if (hasPack) {
            zip = built;
            sha1 = sha1Hex(built);
        }

        Map<String, String> config = readConfig();
        required = "true".equalsIgnoreCase(config.getOrDefault("required", "false"));
        int port = parseInt(config.get("port"), 25568);

        String host = config.getOrDefault("host", "").trim();
        configuredHost = host;
        if (host.isEmpty()) {
            host = guessHost();
        }
        // 先起服务再决定是否成功：端口被占用时只关掉投送，不影响主服。
        if (!listen(port)) {
            return "HTTP 端口 " + port + " 不可用，资源包投送与 mod 发布关闭";
        }
        baseUrl = "http://" + host + ":" + port;

        StringBuilder summary = new StringBuilder();
        if (hasPack) {
            summary.append("资源包 ").append(kib(built.length))
                    .append(" · sha1 ").append(sha1, 0, 8)
                    .append(required ? " · 强制接受" : "");
        } else {
            summary.append("资源包未启用（").append(packNote).append("）");
        }
        if (modNote != null) {
            summary.append("；").append(modNote);
        }
        summary.append("；HTTP ").append(baseUrl)
                .append("（资源包用此地址；mod 下载链接按客户端 Host 回写）");
        return summary.toString();
    }

    static void stop() {
        running = false;
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭失败不影响停机
            }
        }
        listener = null;
        zip = null;
        sha1 = "";
        baseUrl = "";
        configuredHost = "";
    }

    /**
     * 动态定义变化后热重建资源包，并对在线玩家重推。
     *
     * <p>sha1 没变（比如只重编译了行为词库）就直接跳过，避免全服空重下一次；
     * 变了的话 UUID 由新 sha1 派生，客户端自然重下。开服阶段还没人上线，
     * 所以同名重推不会打扰玩家。</p>
     */
    public static void rebuildAndRepush(MinecraftServer server) {
        Path pluginRoot = FMLPaths.GAMEDIR.get().resolve("nebula");
        if (!Files.isDirectory(pluginRoot)) {
            return;
        }
        try {
            byte[] built = buildZip(pluginRoot);
            if (built == null || built.length == 0) {
                return;
            }
            String hash = sha1Hex(built);
            if (hash.equals(sha1)) {
                return;
            }
            zip = built;
            sha1 = hash;
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    push(player);
                }
            }
        } catch (Exception e) {
            LOG.warn("[星云] 资源包热重建失败：{}", e.toString());
        }
    }

    /** 玩家进服时推送。客户端接受后自行下载并缓存，服务端不需要后续动作。 */
    static void push(ServerPlayer player) {
        byte[] payload = zip;
        String hash = sha1;
        String base = baseUrl;
        if (payload == null || hash.isEmpty() || base.isEmpty() || player == null) {
            return;
        }
        try {
            // 同一个资源包固定 UUID，客户端按 hash 命中缓存就不会重复下载。
            UUID id = UUID.nameUUIDFromBytes(("nebula-pack:" + hash).getBytes(StandardCharsets.UTF_8));
            player.connection.send(new ClientboundResourcePackPushPacket(
                    id,
                    base + "/pack.zip",
                    hash,
                    required,
                    Optional.of(Component.literal("§b星云资源包§r §7· 自定义物品与生物外观"))));
        } catch (Throwable t) {
            LOG.warn("[星云] 资源包推送失败：{}", t.toString());
        }
    }

    // ────────────────────────────── 打包 ──────────────────────────────

    /**
     * 从各插件的 {@code 资源/} 生成资源包 zip。条目时间戳固定为 0，内容不变则字节不变，
     * sha1 稳定，客户端能命中缓存、不会每次进服都重下。
     *
     * <p>没有共享底板目录：中文类型目录由 {@link #pluginAssetRoots} 翻译成标准包路径
     * （{@code 资源/物品材质/a.png} → {@code assets/nebula/textures/item/a.png}），
     * {@code pack.mcmeta} 由本类内建。按条目名排序写盘，所以包内路径不变时挪目录也不会改变 sha1。</p>
     *
     * @return 一个资源条目都没有时返回 {@code null}，避免给客户端推一个空包
     */
    static byte[] buildZip(Path pluginRoot) throws IOException {
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>();
        for (AssetRoot assetRoot : pluginAssetRoots(pluginRoot)) {
            try (Stream<Path> walk = Files.walk(assetRoot.dir())) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = assetRoot.prefix()
                            + assetRoot.dir().relativize(file).toString().replace('\\', '/');
                    // 同名资源按插件目录名排序先到先得；重名记一条 WARN，别让作者猜哪份生效。
                    byte[] previous = entries.putIfAbsent(name, Files.readAllBytes(file));
                    if (previous != null) {
                        LOG.warn("[星云] 插件资源重名，后者被忽略：{}", name);
                    }
                }
            }
        }
        // 创造页声明：词库写在 <插件>/创造物品.json。客户端的创造页是**本地**重建的
        // （CreativeModeInventoryScreen#tryRebuildTabContents），它读不到服务器的 gameDir，
        // 唯一能跟着进客户端的通道就是这个资源包，所以顺手打一份到 assets/nebula/creative/。
        int creativeIndex = 0;
        for (Path declaration : pluginCreativeFiles(pluginRoot)) {
            String name = "assets/nebula/creative/" + creativeIndex++ + "_"
                    + slug(declaration.getParent().getFileName().toString()) + ".json";
            entries.putIfAbsent(name, Files.readAllBytes(declaration));
        }
        // 对象派生条目最后写入：动态定义是权威，覆盖同名静态文件。
        // 全量重打是动态卸载能干净撤条目的关键 —— 注册表里没有该条目，重打后旧文件自然消失。
        // 物品先、生物后：万一标记撞名，生物蛋（更特定）赢。
        entries.putAll(nebula.neoforge.content.ItemRegistry.entries());
        entries.putAll(nebula.neoforge.content.MobRegistry.entries());
        entries.putAll(nebula.neoforge.content.NebulaItems.entries());
        if (entries.isEmpty()) {
            return null;
        }
        entries = sanitizePaths(entries);
        entries.put("pack.mcmeta", PACK_META);

        // 客户端声明（更新提示 / 实体外观…）是「到不了客户端就静默失效」的东西，
        // 日志里点一句条数，省得下次又靠猜。
        int clientDecls = 0;
        for (String name : entries.keySet()) {
            if (name.startsWith("assets/nebula/client/")) {
                clientDecls++;
            }
        }
        LOG.info("[星云] 资源包 {} 条，客户端声明 {} 条", entries.size(), clientDecls);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writeEntry(out, entry.getKey(), entry.getValue());
            }
        }
        return buffer.toByteArray();
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /** 收集 {@code nebula/<插件>/创造物品.json}（跳过下划线目录）。 */
    static List<Path> pluginCreativeFiles(Path pluginRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        if (pluginRoot == null || !Files.isDirectory(pluginRoot)) {
            return out;
        }
        try (Stream<Path> children = Files.list(pluginRoot)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || name.startsWith("_") || name.startsWith(".")) {
                    continue;
                }
                Path file = child.resolve("创造物品.json");
                if (Files.isRegularFile(file)) {
                    out.add(file);
                }
            }
        }
        return out;
    }

    /** 目录名 → 资源路径里安全的片段（Identifier 只认小写字母 / 数字 / `_` / `-` / `.`）。 */
    private static String slug(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? "plugin" : sb.toString();
    }

    /**
     * 把包内路径净化为原版白名单字符集。
     *
     * <p>原版 {@code FilePackResources} 只接受 {@code [a-z0-9_.-]} 的路径段，
     * 含中文的文件名会被<b>整条丢弃</b>并刷
     * {@code "Invalid path in datapack: nebula:client/mob_箱子生物.json, ignoring"} ——
     * 那份声明等于不存在（箱子生物「不显示」就是这么来的）。</p>
     *
     * <p>声明类文件改名无害（客户端按目录全读，不看文件名）；贴图 / 模型改名会让词库里
     * 引用的旧名字失效，所以一并 WARN，让作者把名字改成 ASCII。</p>
     */
    private static java.util.TreeMap<String, byte[]> sanitizePaths(
            java.util.TreeMap<String, byte[]> raw) {
        java.util.TreeMap<String, byte[]> out = new java.util.TreeMap<>();
        for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String safe = sanitizePath(entry.getKey());
            if (!safe.equals(entry.getKey())) {
                LOG.warn("[星云] 资源包路径含非 ASCII 字符，客户端会忽略；已改写 {} → {}"
                        + "（插件请改用英文 / 数字文件名）", entry.getKey(), safe);
            }
            if (out.put(safe, entry.getValue()) != null) {
                LOG.warn("[星云] 路径净化后撞名，后者被忽略：{}", safe);
            }
        }
        return out;
    }

    /** 逐段净化；非法段按「slug + 短哈希 + 原扩展名」改名，稳定且不会互撞。 */
    private static String sanitizePath(String path) {
        StringBuilder out = new StringBuilder(path.length() + 8);
        for (String segment : path.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(safeSegment(segment));
        }
        return out.toString();
    }

    private static String safeSegment(String segment) {
        if (segment.isEmpty() || pathSafe(segment)) {
            return segment;
        }
        int dot = segment.lastIndexOf('.');
        String stem = dot > 0 ? segment.substring(0, dot) : segment;
        String ext = dot > 0 ? segment.substring(dot) : "";
        return slug(stem) + "_" + Integer.toHexString(segment.hashCode() & 0x7fffffff) + ext;
    }

    /** 原版 {@code FilePackResources} 的路径白名单：小写字母 / 数字 / {@code _ - .}。 */
    private static boolean pathSafe(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一个资源根：{@code dir} 下的文件会写到包内 {@code prefix + 相对路径}。
     *
     * <p>比如插件 {@code 资源/物品模型/a.json} 会变成 {@code assets/nebula/models/item/a.json}。</p>
     */
    record AssetRoot(Path dir, String prefix) {
    }

    /** 中文资源类型目录 → （包内目录, 默认命名空间）。 */
    private record Kind(String dir, String namespace) {
    }

    private static final java.util.LinkedHashMap<String, Kind> RESOURCE_KINDS =
            new java.util.LinkedHashMap<>();

    static {
        // 模型的 item_model 组件值统一是 `nebula:item/<名字>`，所以物品列表落到 items/item/
        RESOURCE_KINDS.put("物品列表", new Kind("items/item", "nebula"));
        RESOURCE_KINDS.put("物品模型", new Kind("models/item", "nebula"));
        RESOURCE_KINDS.put("物品材质", new Kind("textures/item", "nebula"));
        RESOURCE_KINDS.put("方块状态", new Kind("blockstates", "nebula_bridge"));
        RESOURCE_KINDS.put("方块模型", new Kind("models/block", "nebula_bridge"));
        RESOURCE_KINDS.put("方块材质", new Kind("textures/block", "nebula_bridge"));
        RESOURCE_KINDS.put("实体材质", new Kind("textures/entity", "nebula_bridge"));
        RESOURCE_KINDS.put("粒子", new Kind("particles", "nebula_bridge"));
        RESOURCE_KINDS.put("音效", new Kind("sounds", "nebula_bridge"));
        RESOURCE_KINDS.put("语言", new Kind("lang", "nebula_bridge"));
        RESOURCE_KINDS.put("界面", new Kind("textures/gui", "nebula_bridge"));
        // 客户端声明（实体外观 / 更新提示…）：由 ClientDecls 从资源包读取，mod 不含内容。
        RESOURCE_KINDS.put("客户端", new Kind("client", "nebula"));
    }

    /**
     * 收集插件自带的资源目录（跳过 {@code _docs} 这类下划线目录）。
     *
     * <p>插件资源只有一条路：{@code <插件>/资源/<类型>/...}。</p>
     * <ul>
     *   <li><b>中文类型目录</b>：{@code 资源/物品列表/a.json} → {@code assets/nebula/items/item/a.json}，
     *       类型到「包内目录 + 默认命名空间」的映射见 {@link #RESOURCE_KINDS}；</li>
     *   <li><b>原样命名空间</b>：{@code 资源/<命名空间>/...} → {@code assets/<命名空间>/...}，
     *       给映射表没覆盖的东西（font、shaders、sound.json 和具体 json 同目录的特殊格式……）留后门。</li>
     * </ul>
     * 不再支持 {@code <插件>/assets/...}：同样的东西有两套目录写法只会让人猜。
     */
    static List<AssetRoot> pluginAssetRoots(Path pluginRoot) throws IOException {
        List<AssetRoot> out = new ArrayList<>();
        if (pluginRoot == null || !Files.isDirectory(pluginRoot)) {
            return out;
        }
        try (Stream<Path> children = Files.list(pluginRoot)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || name.startsWith("_") || name.startsWith(".")) {
                    continue;
                }
                // 唯一的写法：<插件>/资源/<类型>/...
                Path chinese = child.resolve("资源");
                if (!Files.isDirectory(chinese)) {
                    continue;
                }
                try (Stream<Path> kinds = Files.list(chinese)) {
                    for (Path kindDir : kinds.sorted().toList()) {
                        if (!Files.isDirectory(kindDir)) {
                            continue;
                        }
                        String kindName = kindDir.getFileName().toString();
                        Kind kind = RESOURCE_KINDS.get(kindName);
                        if (kind == null) {
                            // 不是已知类型 → 当成命名空间，原样映射
                            out.add(new AssetRoot(kindDir, "assets/" + kindName + "/"));
                        } else {
                            out.add(new AssetRoot(kindDir,
                                    "assets/" + kind.namespace() + "/" + kind.dir() + "/"));
                        }
                    }
                }
            }
        }
        return out;
    }

    static String sha1Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] sum = digest.digest(data);
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ────────────────────────────── HTTP ──────────────────────────────

    /**
     * 极简 HTTP 服务：只认 GET /pack.zip，其余一律 404。手写而不引 HttpServer，
     * 是因为 jdk.httpserver 在模块化启动下未必被解析，而 ServerSocket 永远可用。
     */
    private static boolean listen(int port) {
        try {
            ServerSocket created = new ServerSocket();
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(port), 16);
            socket = created;
            running = true;
        } catch (Exception e) {
            LOG.warn("[星云] 资源包 HTTP 端口 {} 绑定失败：{}", port, e.toString());
            return false;
        }
        Thread thread = new Thread(NebulaPack::serve, "nebula-pack-http");
        thread.setDaemon(true);
        thread.start();
        listener = thread;
        return true;
    }

    private static void serve() {
        while (running) {
            ServerSocket server = socket;
            if (server == null) {
                return;
            }
            try (Socket client = server.accept()) {
                client.setSoTimeout(8000);
                handle(client);
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                // 单个连接出错（客户端提前断开 / 扫描器探活）不能拖垮整个监听。
            }
        }
    }

    private static void handle(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = reader.readLine();
        if (request == null) {
            return;
        }
        // 吃掉请求头直到空行，避免客户端在写响应时仍在发送导致 RST。
        // 只留 Host：客户端下载链接必须按它回写，见 requestBase()。
        String hostHeader = "";
        String line;
        int guard = 0;
        while ((line = reader.readLine()) != null && !line.isEmpty() && guard++ < 64) {
            if (hostHeader.isEmpty() && line.regionMatches(true, 0, "Host:", 0, 5)) {
                hostHeader = line.substring(5).trim();
            }
        }
        OutputStream out = client.getOutputStream();

        // 1) 资源包
        if (request.startsWith("GET /pack.zip")) {
            byte[] payload = zip;
            if (payload == null) {
                status(out, 503, "Service Unavailable");
                return;
            }
            writeResponse(out, "application/zip", payload);
            return;
        }
        // 2) mod 版本清单
        if (request.startsWith("GET /nebula/mod/manifest.json")) {
            String json = NebulaModDistributor.rawManifest(requestBase(hostHeader));
            if (json == null) {
                status(out, 503, "Service Unavailable");
                return;
            }
            writeResponse(out, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 3) mod jar 本体
        if (request.startsWith("GET /nebula/mod/latest.jar")) {
            byte[] data = NebulaModDistributor.rawJar();
            if (data == null) {
                status(out, 503, "Service Unavailable");
                return;
            }
            writeResponse(out, "application/java-archive", data);
            return;
        }
        // 4) 自定义皮肤 PNG：{@code /nebula/skins/<文件名>?v=<指纹>}。
        //    文件名由 SkinService 校验（只认 nebula/_skins 与各插件 皮肤/ 下的裸文件名），
        //    这里切掉 query 后直接交给它，路径穿越在第一道就被拒。
        if (request.startsWith("GET " + SkinService.SKIN_PATH)) {
            String target = request.substring(("GET " + SkinService.SKIN_PATH).length());
            int space = target.indexOf(' ');
            if (space >= 0) {
                target = target.substring(0, space);
            }
            int query = target.indexOf('?');
            if (query >= 0) {
                target = target.substring(0, query);
            }
            try {
                target = java.net.URLDecoder.decode(target, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 解不开就按原文找，找不到自然 404
            }
            byte[] png = SkinService.skinBytes(target);
            if (png == null) {
                status(out, 404, "Not Found");
                return;
            }
            writeResponse(out, "image/png", png);
            return;
        }
        status(out, 404, "Not Found");
    }

    /**
     * 按「请求方实际用的地址」拼下载基地址，用于 manifest 里的 jar 下载链接。
     *
     * <p>{@code baseUrl} 是服务端自己猜的（server-ip / 第一个私网 IPv4），对玩家不一定可达：
     * 有人从公网域名进来、有人走 frp 隧道、有人就在本机用 {@code localhost}，
     * 而服务端猜出来的往往是一条 192.168.x.x 的局域网地址。Host 头是客户端亲手写的、
     * 它一定连得上，所以拿它回写链接才是对的。头里通常自带端口，缺端口时补监听端口。</p>
     */
    private static String requestBase(String hostHeader) {
        if (!configuredHost.isEmpty() && !baseUrl.isEmpty()) {
            // 显式配了对外地址（公网域名 / frp 端口）就按配置来，别被单个玩家的 Host 头改掉。
            return baseUrl;
        }
        String host = hostHeader == null ? "" : hostHeader.trim();
        if (host.isEmpty()) {
            return baseUrl;
        }
        int port = 0;
        ServerSocket current = socket;
        if (current != null) {
            port = current.getLocalPort();
        }
        if (host.startsWith("[")) {
            // IPv6 字面量：[::1] 或 [::1]:25568
            int close = host.indexOf(']');
            if (close < 0) {
                return baseUrl;
            }
            String rest = host.substring(close + 1);
            if (rest.startsWith(":") && rest.length() > 1) {
                return "http://" + host;
            }
            String bare = host.substring(0, close + 1);
            return port > 0 ? "http://" + bare + ":" + port : "http://" + bare;
        }
        int first = host.indexOf(':');
        int last = host.lastIndexOf(':');
        if (first > 0 && first == last) {
            // host:port —— 常见情况，原样用
            return "http://" + host;
        }
        if (first > 0) {
            // 没加方括号的裸 IPv6，补上再拼端口
            return port > 0 ? "http://[" + host + "]:" + port : "http://[" + host + "]";
        }
        return port > 0 ? "http://" + host + ":" + port : "http://" + host;
    }

    private static void writeResponse(OutputStream out, String contentType, byte[] payload)
            throws IOException {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    private static void status(OutputStream out, int code, String text) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    // ────────────────────────────── 配置 ──────────────────────────────

    private static Map<String, String> readConfig() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("nebula-bridge").resolve(CONFIG_FILE);
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return map;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = raw.trim();
                if (text.isEmpty() || text.startsWith("#") || text.startsWith(";")) {
                    continue;
                }
                int eq = text.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                map.put(text.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT),
                        text.substring(eq + 1).trim());
            }
        } catch (Exception e) {
            LOG.warn("[星云] 读取 {} 失败：{}", CONFIG_FILE, e.toString());
        }
        return map;
    }

    /**
     * 猜一个客户端能访问到的地址：优先 server.properties 的 server-ip，
     * 其次本机第一个私网 IPv4，最后退回回环地址（仅本机玩时有效）。
     */
    private static String guessHost() {
        String fromProperties = serverIpProperty();
        if (!fromProperties.isEmpty() && !"0.0.0.0".equals(fromProperties)) {
            return fromProperties;
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 探测失败就走回环
        }
        return "127.0.0.1";
    }

    private static String serverIpProperty() {
        Path file = FMLPaths.GAMEDIR.get().resolve("server.properties");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = raw.trim();
                if (text.startsWith("server-ip=")) {
                    return text.substring("server-ip=".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // 读不到就返回空
        }
        return "";
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value < 65536 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String kib(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

}
