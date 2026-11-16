package nebula.neoforge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.common.collect.HashMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Unsafe;

/**
 * 玩家皮肤：把「谁长什么样」写进 {@link GameProfile} 的 {@code textures} 属性，再刷新玩家列表。
 *
 * <p>Minecraft 的皮肤是客户端按 profile 里的 {@code textures} 属性自己下载的，服务端不参与渲染。
 * 所以换肤只有一条路：改 profile 属性 → 让客户端重建 {@code PlayerInfo}（Remove + Add）。
 * 本类只做机制，指令 / 文案 / 目录约定全在 {@code nebula/外观/main.n}。</p>
 *
 * <p>两种来源：</p>
 * <ul>
 *   <li><b>正版账号</b>：向 Mojang 拉 <i>signed</i> 的 textures（客户端校验签名，原版客户端也能看到）；</li>
 *   <li><b>自定义 PNG</b>：服务端本地托管，textures 里的 URL 用占位域名
 *       {@code nebula.invalid}，装本 mod 的客户端会把它换成「当前服务器地址 + HTTP 端口」再下载
 *       （原版客户端只认 {@code textures.minecraft.net}，看不到自定义皮肤，这是原版限制）。</li>
 * </ul>
 *
 * <p>持久化在 {@code config/nebula-bridge/skins.json}，玩家进服时自动重放；清除时恢复登录自带的
 * textures（离线服则直接删属性）。</p>
 */
public final class SkinService {
    private static final Logger LOG = LogManager.getLogger("nebula");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 自定义皮肤的占位域名，客户端按「当前服务器 host + 端口」替换。 */
    public static final String PLACEHOLDER_HOST = "nebula.invalid";
    /** 占位 URL 的路径前缀；HTTP 服务与客户端只认这一条。 */
    public static final String SKIN_PATH = "/nebula/skins/";
    /** 自定义皮肤缓存目录，下划线开头：词库引擎不会把它当插件扫描。 */
    private static final String CACHE_DIR = "_skins";
    /** 自定义披风缓存目录（从链接下载下来的那些）。 */
    private static final String CAPE_CACHE_DIR = "_capes";

    private static final long OFFICIAL_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_PNG_BYTES = 512 * 1024;
    private static final Pattern SKIN_FILE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 玩家当前的外观记录；{@code kind} 为 正版 / 文件 / 链接，披风另记一份。 */
    static final class Entry {
        String kind = "";
        String value = "";
        String model = "wide";
        String capeKind = "";
        String capeValue = "";
        String textureValue = "";
        String textureSignature = "";
        long updatedAt = 0L;
    }

    /** 玩家登录时 profile 自带的 textures（清除时恢复）。 */
    private record Original(String value, String signature) {
    }

    private static volatile Unsafe UNSAFE;

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Original> ORIGINALS = new ConcurrentHashMap<>();

    private SkinService() {
    }

    // ────────────────────────────── 生命周期 ──────────────────────────────

    /** 开服时读盘；词库目录还不存在也不报错。 */
    static void start() {
        ENTRIES.clear();
        ORIGINALS.clear();
        Path file = storeFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject players = root.has("players") && root.get("players").isJsonObject()
                    ? root.getAsJsonObject("players") : new JsonObject();
            for (var item : players.entrySet()) {
                try {
                    UUID id = UUID.fromString(item.getKey());
                    if (!item.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject json = item.getValue().getAsJsonObject();
                    Entry entry = new Entry();
                    entry.kind = str(json, "类型");
                    entry.value = str(json, "值");
                    entry.model = str(json, "模型").isEmpty() ? "wide" : str(json, "模型");
                    entry.capeKind = str(json, "披风类型");
                    entry.capeValue = str(json, "披风值");
                    entry.textureValue = str(json, "皮肤值");
                    entry.textureSignature = str(json, "签名");
                    entry.updatedAt = json.has("更新") ? json.get("更新").getAsLong() : 0L;
                    if (!entry.kind.isEmpty() || !entry.capeKind.isEmpty()) {
                        ENTRIES.put(id, entry);
                    }
                } catch (Exception ignored) {
                    // 单条坏了不影响其它玩家
                }
            }
            LOG.info("[星云] 皮肤记录 {} 条", ENTRIES.size());
        } catch (Exception e) {
            LOG.warn("[星云] 皮肤记录读取失败：{}", e.toString());
        }
    }

    static void stop() {
        save();
    }

    /** 玩家进服：记住原始皮肤，再重放已保存的选择。 */
    static void onJoin(MinecraftServer server, ServerPlayer player) {
        rememberOriginal(player);
        Entry entry = ENTRIES.get(player.getUUID());
        if (entry == null) {
            return;
        }
        // 记录里存的是完整 textures 快照（皮肤 + 披风一起），直接重放不会丢另一半。
        if (!entry.textureValue.isEmpty()) {
            if ("正版".equals(entry.kind)
                    && System.currentTimeMillis() - entry.updatedAt >= OFFICIAL_TTL_MS) {
                // 签名值会过期，过期就重新拉一次
                fetchOfficial(server, player, entry.value, true);
                return;
            }
            if (applyProperty(player, entry.textureValue, entry.textureSignature)) {
                refresh(player);
            }
            return;
        }
        // 旧记录（只有文件名、没有快照）：按名字重扫目录
        if ("文件".equals(entry.kind) || "链接".equals(entry.kind)) {
            applyFile(server, player, entry.value, entry.model, entry.kind, true);
        } else if ("正版".equals(entry.kind)) {
            fetchOfficial(server, player, entry.value, true);
        }
    }

    // ────────────────────────────── 入口（词库动作） ──────────────────────────────

    /**
     * 词库动作 {@code $世界.皮肤 操作 玩家 参数$}。
     *
     * <p>操作：{@code 正版 <名字>} / {@code 文件 <文件名>} / {@code 链接 <URL>} /
     * {@code 清除} / {@code 刷新}；披风：{@code 披风文件 <文件名>} / {@code 披风链接 <URL>} /
     * {@code 披风清除}。</p>
     *
     * <p>皮肤与披风共用同一个 {@code textures} 属性，所以每次只改一半、另一半从当前 profile 里
     * 原样搬过来（{@link #currentTextures}）。这也是「换皮肤不该顺手把披风弄没」的原因。</p>
     */
    static void action(MinecraftServer server, JsonObject action) {
        if (server == null || action == null) {
            return;
        }
        String op = McSupport.str(action, "操作").trim();
        String target = McSupport.str(action, "玩家").trim();
        String arg = McSupport.str(action, "参数").trim();
        ServerPlayer player = McSupport.player(server, target);
        if (player == null) {
            LOG.warn("[星云] 皮肤：找不到玩家 {}", target);
            return;
        }
        switch (op) {
            case "正版", "正版名" -> fetchOfficial(server, player, arg, false);
            case "文件", "本地" -> applyFile(server, player, arg, "wide", "文件", false);
            case "链接", "网址", "url" -> downloadAndApply(server, player, arg);
            case "披风文件", "披风" -> applyCapeFile(server, player, arg, false);
            case "披风链接" -> downloadCape(server, player, arg);
            case "披风清除" -> clearCape(server, player);
            case "清除", "重置" -> clear(server, player);
            // 只重发 PlayerInfo，不重放记录：onJoin 会重新记录「原始皮肤」，
            // 此刻 profile 已经是自定义的，会把恢复用的底稿覆盖掉。
            case "刷新" -> refresh(player);
            default -> LOG.warn("[星云] 皮肤：未知操作 {}", op);
        }
    }

    /** 查询 {@code $世界.取皮肤 玩家$}：给词库显示状态用。 */
    static String describe(MinecraftServer server, String target) {
        JsonObject out = new JsonObject();
        ServerPlayer player = McSupport.player(server, target);
        if (player == null) {
            out.addProperty("玩家", target);
            out.addProperty("类型", "无");
            out.addProperty("来源", "");
            out.addProperty("模型", "");
            out.addProperty("有皮肤", "否");
            out.addProperty("披风类型", "无");
            out.addProperty("披风来源", "");
            out.addProperty("有披风", "否");
            return out.toString();
        }
        out.addProperty("玩家", player.getGameProfile().name());
        Entry entry = ENTRIES.get(player.getUUID());
        out.addProperty("类型", entry == null || entry.kind.isEmpty() ? "无" : entry.kind);
        out.addProperty("来源", entry == null ? "" : entry.value);
        out.addProperty("模型", entry == null || entry.model.isEmpty() ? "wide" : entry.model);
        Property textures = firstTexture(player);
        out.addProperty("有皮肤", textures == null ? "否" : "是");
        out.addProperty("签名", textures != null && textures.hasSignature() ? "是" : "否");
        out.addProperty("披风类型", entry == null || entry.capeKind.isEmpty() ? "无" : entry.capeKind);
        out.addProperty("披风来源", entry == null ? "" : entry.capeValue);
        out.addProperty("有披风", hasTexture(player, "CAPE") ? "是" : "否");
        out.addProperty("更新", entry == null ? 0L : entry.updatedAt);
        return out.toString();
    }

    // ────────────────────────────── 正版账号 ──────────────────────────────

    private static void fetchOfficial(MinecraftServer server, ServerPlayer player, String rawName, boolean quiet) {
        String name = rawName.trim();
        if (name.isEmpty()) {
            warn(player, "请填写正版账号名，例如 /皮肤 正版 Notch");
            return;
        }
        if (!MOJANG_NAME.matcher(name).matches()) {
            warn(player, "正版账号名只能是字母、数字、下划线（1-16 位）");
            return;
        }
        CompletableFuture
                .supplyAsync(() -> fetchMojang(name))
                .thenAccept(result -> server.execute(() -> {
                    if (result == null) {
                        warn(player, "拉取 " + name + " 的皮肤失败（账号不存在 / 无外网 / Mojang 超时）");
                        return;
                    }
                    if (!applyProperty(player, result[0], result[1])) {
                        warn(player, "写入皮肤失败：玩家资料不可修改");
                        return;
                    }
                    Entry entry = new Entry();
                    entry.kind = "正版";
                    entry.value = result[2];
                    entry.model = result[3];
                    entry.textureValue = result[0];
                    entry.textureSignature = result[1];
                    if (textureUrlOf(result[0], "CAPE") != null) {
                        // 该账号自带披风：整份值都是签名的，一起换上
                        entry.capeKind = "正版";
                        entry.capeValue = result[2];
                    }
                    entry.updatedAt = System.currentTimeMillis();
                    ENTRIES.put(player.getUUID(), entry);
                    save();
                    refresh(player);
                    if (!quiet) {
                        notify(player, "§a已换成 §f" + result[2] + " §a的皮肤");
                    }
                }))
                .exceptionally(error -> {
                    LOG.warn("[星云] 皮肤：正版拉取异常 {}", error.toString());
                    return null;
                });
    }

    /** 返回 {value, signature, 正版名, 模型}；失败返回 null。 */
    private static String[] fetchMojang(String name) {
        try {
            JsonObject profile = getJson("https://api.mojang.com/users/profiles/minecraft/"
                    + URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (profile == null || !profile.has("id")) {
                return null;
            }
            String id = profile.get("id").getAsString();
            String canonical = profile.has("name") ? profile.get("name").getAsString() : name;
            JsonObject full = getJson("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + id + "?unsigned=false");
            if (full == null || !full.has("properties")) {
                return null;
            }
            JsonArray properties = full.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(str(property, "name"))) {
                    continue;
                }
                String value = str(property, "value");
                String signature = str(property, "signature");
                if (value.isEmpty()) {
                    continue;
                }
                return new String[]{value, signature, canonical, modelOf(value)};
            }
            return null;
        } catch (Exception e) {
            LOG.warn("[星云] 正版皮肤拉取失败（{}）：{}", name, e.toString());
            return null;
        }
    }

    // ────────────────────────────── 自定义 PNG ──────────────────────────────

    private static void applyFile(MinecraftServer server, ServerPlayer player, String fileName,
                                  String model, String kind, boolean quiet) {
        Path file = findSkin(fileName);
        if (file == null) {
            warn(player, "找不到皮肤文件 " + fileName + "（放在 nebula/外观/皮肤/ 或 nebula/_skins/）");
            return;
        }
        byte[] png;
        try {
            png = Files.readAllBytes(file);
        } catch (IOException e) {
            warn(player, "皮肤文件读取失败：" + e.getMessage());
            return;
        }
        String problem = checkPng(png);
        if (problem != null) {
            warn(player, problem);
            return;
        }
        String hash = sha1Hex(png).substring(0, 10);
        String url = "http://" + PLACEHOLDER_HOST + SKIN_PATH
                + urlEncode(file.getFileName().toString()) + "?v=" + hash;
        // 只换皮肤：当前披风（可能有）原样搬过来，不会被顺手抹掉
        String value = texturesValue(player, url, model, currentCapeUrl(player));
        if (!applyProperty(player, value, null)) {
            warn(player, "写入皮肤失败：玩家资料不可修改");
            return;
        }
        Entry entry = ENTRIES.computeIfAbsent(player.getUUID(), id -> new Entry());
        entry.kind = kind;
        entry.value = file.getFileName().toString();
        entry.model = model;
        entry.textureValue = value;
        entry.textureSignature = "";
        entry.updatedAt = System.currentTimeMillis();
        save();
        refresh(player);
        if (!quiet) {
            notify(player, "§a已换成自定义皮肤 §f" + file.getFileName());
        }
    }

    private static void downloadAndApply(MinecraftServer server, ServerPlayer player, String rawUrl) {
        String url = rawUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            warn(player, "链接要以 http:// 或 https:// 开头");
            return;
        }
        if (url.length() > 512) {
            warn(player, "链接太长了");
            return;
        }
        notify(player, "§7正在下载皮肤…");
        CompletableFuture
                .supplyAsync(() -> downloadPng(url))
                .thenAccept(png -> server.execute(() -> {
                    if (png == null) {
                        warn(player, "下载失败（超时 / 不是图片 / 超过 512KB）");
                        return;
                    }
                    String problem = checkPng(png);
                    if (problem != null) {
                        warn(player, problem);
                        return;
                    }
                    try {
                        Path dir = cacheDir(CACHE_DIR);
                        String hash = sha1Hex(png).substring(0, 10);
                        Path file = dir.resolve(player.getUUID().toString().replace("-", "")
                                + "-" + hash + ".png");
                        Files.write(file, png);
                        applyFile(server, player, file.getFileName().toString(), "wide", "链接", false);
                    } catch (Exception e) {
                        warn(player, "保存皮肤失败：" + e.getMessage());
                    }
                }))
                .exceptionally(error -> {
                    LOG.warn("[星云] 皮肤：链接下载异常 {}", error.toString());
                    return null;
                });
    }

    // ────────────────────────────── 自定义披风 ──────────────────────────────

    private static void applyCapeFile(MinecraftServer server, ServerPlayer player, String fileName,
                                      boolean quiet) {
        Path file = findCape(fileName);
        if (file == null) {
            warn(player, "找不到披风文件 " + fileName + "（放在 nebula/外观/披风/ 或 nebula/_capes/）");
            return;
        }
        byte[] png;
        try {
            png = Files.readAllBytes(file);
        } catch (IOException e) {
            warn(player, "披风文件读取失败：" + e.getMessage());
            return;
        }
        String problem = checkCapePng(png);
        if (problem != null) {
            warn(player, problem);
            return;
        }
        String hash = sha1Hex(png).substring(0, 10);
        String url = "http://" + PLACEHOLDER_HOST + SKIN_PATH
                + urlEncode(file.getFileName().toString()) + "?v=" + hash;
        if (writeCape(player, url, file.getFileName().toString(), "文件") && !quiet) {
            notify(player, "§a已换成自定义披风 §f" + file.getFileName());
        }
    }

    private static void downloadCape(MinecraftServer server, ServerPlayer player, String rawUrl) {
        String url = rawUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            warn(player, "链接要以 http:// 或 https:// 开头");
            return;
        }
        if (url.length() > 512) {
            warn(player, "链接太长了");
            return;
        }
        notify(player, "§7正在下载披风…");
        CompletableFuture
                .supplyAsync(() -> downloadPng(url))
                .thenAccept(png -> server.execute(() -> {
                    if (png == null) {
                        warn(player, "下载失败（超时 / 不是图片 / 超过 512KB）");
                        return;
                    }
                    String problem = checkCapePng(png);
                    if (problem != null) {
                        warn(player, problem);
                        return;
                    }
                    try {
                        Path dir = cacheDir(CAPE_CACHE_DIR);
                        String hash = sha1Hex(png).substring(0, 10);
                        Path file = dir.resolve(player.getUUID().toString().replace("-", "")
                                + "-" + hash + ".png");
                        Files.write(file, png);
                        applyCapeFile(server, player, file.getFileName().toString(), false);
                    } catch (Exception e) {
                        warn(player, "保存披风失败：" + e.getMessage());
                    }
                }))
                .exceptionally(error -> {
                    LOG.warn("[星云] 披风：链接下载异常 {}", error.toString());
                    return null;
                });
    }

    /** 写披风：皮肤与模型从当前 profile 搬过来，只替换 CAPE 节点。 */
    private static boolean writeCape(ServerPlayer player, String capeUrl, String source, String kind) {
        JsonObject current = currentTextures(player);
        String skinUrl = textureUrl(current, "SKIN");
        String model = modelOfRoot(current);
        String value = texturesValue(player, skinUrl, model, capeUrl);
        if (!applyProperty(player, value, null)) {
            warn(player, "写入披风失败：玩家资料不可修改");
            return false;
        }
        Entry entry = ENTRIES.computeIfAbsent(player.getUUID(), id -> new Entry());
        if (entry.model.isEmpty()) {
            entry.model = model;
        }
        entry.capeKind = kind;
        entry.capeValue = source;
        entry.textureValue = value;
        entry.textureSignature = "";
        entry.updatedAt = System.currentTimeMillis();
        save();
        refresh(player);
        return true;
    }

    private static void clearCape(MinecraftServer server, ServerPlayer player) {
        JsonObject current = currentTextures(player);
        String skinUrl = textureUrl(current, "SKIN");
        if (skinUrl == null) {
            // 没有自定义皮肤：披风只能连同整份 textures 一起还原成登录时的样子
            Original original = ORIGINALS.get(player.getUUID());
            boolean ok = original == null
                    ? removeProperty(player)
                    : applyProperty(player, original.value(), original.signature());
            ENTRIES.remove(player.getUUID());
            save();
            if (ok) {
                refresh(player);
                notify(player, "§a已清除披风");
            } else {
                warn(player, "清除披风失败");
            }
            return;
        }
        String value = texturesValue(player, skinUrl, modelOfRoot(current), null);
        if (!applyProperty(player, value, null)) {
            warn(player, "清除披风失败：玩家资料不可修改");
            return;
        }
        Entry entry = ENTRIES.get(player.getUUID());
        if (entry != null) {
            entry.capeKind = "";
            entry.capeValue = "";
            entry.textureValue = value;
            entry.textureSignature = "";
            entry.updatedAt = System.currentTimeMillis();
            save();
        }
        refresh(player);
        notify(player, "§a已清除披风");
    }

    private static byte[] downloadPng(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "NebulaBridge/0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0 || body.length > MAX_PNG_BYTES) {
                return null;
            }
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private static void clear(MinecraftServer server, ServerPlayer player) {
        Original original = ORIGINALS.get(player.getUUID());
        boolean ok;
        if (original == null) {
            ok = removeProperty(player);
        } else {
            ok = applyProperty(player, original.value(), original.signature());
        }
        ENTRIES.remove(player.getUUID());
        save();
        if (ok) {
            refresh(player);
            notify(player, "§a已恢复默认皮肤");
        } else {
            warn(player, "恢复默认皮肤失败");
        }
    }

    // ────────────────────────────── profile / 刷新 ──────────────────────────────

    private static void rememberOriginal(ServerPlayer player) {
        Property textures = firstTexture(player);
        if (textures == null) {
            ORIGINALS.remove(player.getUUID());
            return;
        }
        ORIGINALS.put(player.getUUID(),
                new Original(textures.value(), textures.signature() == null ? "" : textures.signature()));
    }

    private static Property firstTexture(ServerPlayer player) {
        try {
            var found = player.getGameProfile().properties().get("textures");
            return found.isEmpty() ? null : found.iterator().next();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写入 textures。
     *
     * <p>authlib 7 的 {@link PropertyMap} 构造器会 {@code ImmutableMultimap.copyOf}，
     * 拿到手的表永远只读（{@code put} 直接抛 UnsupportedOperationException）。
     * 所以只能新建一份带 textures 的 PropertyMap，再用 Unsafe 换掉 {@code GameProfile.properties}
     * 这个 final 字段；profile 的 id / name 不动，其它引用（PlayerList、聊天）也不受影响。</p>
     */
    private static boolean applyProperty(ServerPlayer player, String value, String signature) {
        Property property = signature == null || signature.isEmpty()
                ? new Property("textures", value)
                : new Property("textures", value, signature);
        return replaceTextures(player, property);
    }

    private static boolean removeProperty(ServerPlayer player) {
        return replaceTextures(player, null);
    }

    /**
     * texture 为 null 表示删掉 textures 属性。
     *
     * <p>不能改 {@code GameProfile.properties}（record 字段，JDK 21 的 Unsafe 拒绝对 record 取偏移），
     * 所以新建一个 GameProfile 再换掉 {@code Player.gameProfile}——普通类字段，Unsafe 可写。
     * id / name 原样保留，其它引用按 getGameProfile() 现取，不受影响。</p>
     */
    private static boolean replaceTextures(ServerPlayer player, Property texture) {
        try {
            GameProfile profile = player.getGameProfile();
            HashMultimap<String, Property> map = HashMultimap.create();
            for (var entry : profile.properties().entries()) {
                if (!"textures".equals(entry.getKey())) {
                    map.put(entry.getKey(), entry.getValue());
                }
            }
            if (texture != null) {
                map.put("textures", texture);
            }
            GameProfile fresh = new GameProfile(profile.id(), profile.name(), new PropertyMap(map));
            return setFinalField(player, "gameProfile", fresh);
        } catch (Throwable t) {
            LOG.warn("[星云] 皮肤：写 profile 失败", t);
            return false;
        }
    }

    /**
     * 写 record 的 final 字段：先试反射，JDK 21 会拒；再用 Unsafe 直接改内存。
     * 这是唯一路径：authlib 的 PropertyMap 永远只读，必须换掉整个 GameProfile。
     */
    private static boolean setFinalField(Object target, String name, Object value) {
        try {
            Field field = null;
            Class<?> type = target.getClass();
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                LOG.warn("[星云] 皮肤：找不到字段 {}", name);
                return false;
            }
            field.setAccessible(true);
            try {
                field.set(target, value);
                return true;
            } catch (IllegalAccessException ignored) {
                // final：走 Unsafe
            }
            Unsafe unsafe = unsafe();
            if (unsafe == null) {
                return false;
            }
            unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
            return true;
        } catch (Throwable t) {
            LOG.warn("[星云] 皮肤：替换 profile." + name + " 失败", t);
            return false;
        }
    }

    private static Unsafe unsafe() {
        if (UNSAFE != null) {
            return UNSAFE;
        }
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Throwable t) {
            LOG.warn("[星云] 皮肤：拿不到 Unsafe {}", t.toString());
        }
        return UNSAFE;
    }

    /**
     * 让客户端重建这个玩家的 {@code PlayerInfo}：皮肤缓存的主键是「uuid + textures 属性」，
     * 只改服务端对象客户端不会知道。Remove 只清玩家列表条目，不会删除实体。
     */
    static void refresh(ServerPlayer player) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || player == null) {
            return;
        }
        try {
            var list = server.getPlayerList();
            list.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
            list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        } catch (Throwable t) {
            LOG.warn("[星云] 皮肤刷新失败：{}", t.toString());
        }
    }

    // ────────────────────────────── textures JSON ──────────────────────────────

    /** 读 profile 里当前的 textures JSON；没有 / 坏了返回 null。 */
    private static JsonObject currentTextures(ServerPlayer player) {
        Property property = firstTexture(player);
        if (property == null) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root.has("textures") && root.get("textures").isJsonObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 textures 里 SKIN / CAPE 的 url；没有返回 null。 */
    private static String textureUrl(JsonObject root, String kind) {
        if (root == null) {
            return null;
        }
        JsonObject textures = root.getAsJsonObject("textures");
        if (textures == null || !textures.has(kind) || !textures.get(kind).isJsonObject()) {
            return null;
        }
        String url = str(textures.getAsJsonObject(kind), "url");
        return url.isEmpty() ? null : url;
    }

    private static String textureUrlOf(String base64, String kind) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            return textureUrl(JsonParser.parseString(json).getAsJsonObject(), kind);
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentCapeUrl(ServerPlayer player) {
        return textureUrl(currentTextures(player), "CAPE");
    }

    private static boolean hasTexture(ServerPlayer player, String kind) {
        return textureUrl(currentTextures(player), kind) != null;
    }

    /** 从当前 textures 里读皮肤模型（没有 SKIN 就当 wide）。 */
    private static String modelOfRoot(JsonObject root) {
        try {
            JsonObject textures = root.getAsJsonObject("textures");
            JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
            if (skin == null) {
                return "wide";
            }
            String model = str(skin.getAsJsonObject("metadata"), "model");
            return "slim".equalsIgnoreCase(model) ? "slim" : "wide";
        } catch (Exception e) {
            return "wide";
        }
    }

    /** skinUrl / capeUrl 传 null 表示不带该节点。 */
    private static String texturesValue(ServerPlayer player, String skinUrl, String model, String capeUrl) {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("profileId", player.getUUID().toString().replace("-", ""));
        root.addProperty("profileName", player.getGameProfile().name());
        JsonObject textures = new JsonObject();
        if (skinUrl != null && !skinUrl.isEmpty()) {
            JsonObject skin = new JsonObject();
            skin.addProperty("url", skinUrl);
            if ("slim".equalsIgnoreCase(model)) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("model", "slim");
                skin.add("metadata", metadata);
            }
            textures.add("SKIN", skin);
        }
        if (capeUrl != null && !capeUrl.isEmpty()) {
            // 披风没有 metadata 节点，尺寸固定 64×32
            JsonObject cape = new JsonObject();
            cape.addProperty("url", capeUrl);
            textures.add("CAPE", cape);
        }
        root.add("textures", textures);
        return Base64.getEncoder().encodeToString(root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String modelOf(String textureValue) {
        try {
            byte[] decoded = Base64.getDecoder().decode(textureValue);
            JsonObject root = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
            if (skin != null && skin.has("metadata")) {
                String model = str(skin.getAsJsonObject("metadata"), "model");
                if ("slim".equalsIgnoreCase(model)) {
                    return "slim";
                }
            }
        } catch (Exception ignored) {
            // 解析不了就按默认模型
        }
        return "wide";
    }

    // ────────────────────────────── 文件 / HTTP ──────────────────────────────

    /** HTTP 路由 {@code /nebula/skins/<file>} 用：皮肤目录和披风目录都查，读不到返回 null。 */
    static byte[] skinBytes(String fileName) {
        Path file = findSkin(fileName);
        if (file == null) {
            file = findCape(fileName);
        }
        if (file == null) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_PNG_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    static Path findSkin(String rawName) {
        return findPng(rawName, "皮肤", CACHE_DIR);
    }

    static Path findCape(String rawName) {
        return findPng(rawName, "披风", CAPE_CACHE_DIR);
    }

    /**
     * 按文件名找 PNG：先缓存目录，再各插件的 {@code <folder>/} 目录。
     * 只比文件名、不认路径，杜绝 {@code ../} 之类的越权。
     */
    private static Path findPng(String rawName, String folder, String cache) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        if (!wanted.endsWith(".png")) {
            wanted = wanted + ".png";
        }
        for (Path dir : assetDirs(folder, cache)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.sorted().toList()) {
                    if (Files.isRegularFile(file)
                            && file.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                        return file;
                    }
                }
            } catch (IOException ignored) {
                // 目录不存在 / 无权限：换下一个
            }
        }
        return null;
    }

    private static List<Path> assetDirs(String folder, String cache) {
        List<Path> dirs = new ArrayList<>();
        Path root = FMLPaths.GAMEDIR.get().resolve("nebula");
        dirs.add(root.resolve(cache));
        if (Files.isDirectory(root)) {
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(path -> !path.getFileName().toString().startsWith("_"))
                        .map(path -> path.resolve(folder))
                        .filter(Files::isDirectory)
                        .sorted()
                        .forEach(dirs::add);
            } catch (IOException ignored) {
                // 词库目录不可读就只认缓存目录
            }
        }
        return dirs;
    }

    private static Path cacheDir(String name) throws IOException {
        Path dir = FMLPaths.GAMEDIR.get().resolve("nebula").resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private static Path storeFile() {
        return FMLPaths.CONFIGDIR.get().resolve("nebula-bridge").resolve("skins.json");
    }

    private static synchronized void save() {
        try {
            JsonObject players = new JsonObject();
            for (var item : ENTRIES.entrySet()) {
                JsonObject json = new JsonObject();
                json.addProperty("类型", item.getValue().kind);
                json.addProperty("值", item.getValue().value);
                json.addProperty("模型", item.getValue().model);
                json.addProperty("披风类型", item.getValue().capeKind);
                json.addProperty("披风值", item.getValue().capeValue);
                json.addProperty("皮肤值", item.getValue().textureValue);
                json.addProperty("签名", item.getValue().textureSignature);
                json.addProperty("更新", item.getValue().updatedAt);
                players.add(item.getKey().toString(), json);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("players", players);
            Path file = storeFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[星云] 皮肤记录保存失败：{}", e.toString());
        }
    }

    private static JsonObject getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "NebulaBridge/0.5")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isEmpty()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(response.body());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ────────────────────────────── 校验 / 工具 ──────────────────────────────

    /** 只认 64×64（现代）与 64×32（旧版）两种尺寸，避免把任意图片塞进皮肤槽。 */
    private static String checkPng(byte[] data) {
        if (data == null || data.length < 24) {
            return "皮肤文件不是有效的 PNG";
        }
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return "皮肤文件不是有效的 PNG";
            }
        }
        int width = readInt(data, 16);
        int height = readInt(data, 20);
        if (width == 64 && (height == 64 || height == 32)) {
            return null;
        }
        return "皮肤尺寸必须是 64×64 或 64×32（当前 " + width + "×" + height + "）";
    }

    /** 披风固定 64×32：客户端只读左上 10×16 那块，尺寸不对会整块拉花。 */
    private static String checkCapePng(byte[] data) {
        if (data == null || data.length < 24) {
            return "披风文件不是有效的 PNG";
        }
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return "披风文件不是有效的 PNG";
            }
        }
        int width = readInt(data, 16);
        int height = readInt(data, 20);
        if (width == 64 && height == 32) {
            return null;
        }
        return "披风尺寸必须是 64×32（当前 " + width + "×" + height + "）";
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder builder = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return "0000000000";
        }
    }

    private static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String str(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull()
                    ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void notify(ServerPlayer player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    private static void warn(ServerPlayer player, String text) {
        notify(player, "§c[外观] §7" + text);
    }

    /** 供词库 {@code /外观 列表} 之类的调试输出。 */
    static String dump(MinecraftServer server) {
        JsonArray list = new JsonArray();
        for (var item : new LinkedHashMap<>(ENTRIES).entrySet()) {
            ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(item.getKey());
            JsonObject json = new JsonObject();
            json.addProperty("uuid", item.getKey().toString());
            json.addProperty("玩家", player == null ? "" : player.getGameProfile().name());
            json.addProperty("类型", item.getValue().kind);
            json.addProperty("值", item.getValue().value);
            list.add(json);
        }
        return list.toString();
    }
}
