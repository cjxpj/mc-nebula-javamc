package nebula.neoforge.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端更新检查：进服后向服务端的 {@code /nebula/mod/manifest.json} 请求版本，
 * 比本地 mod 版本新就在聊天栏弹一条可点击的提示。
 *
 * <p><b>点击去哪：</b>优先用声明里的 {@code GitHub}（发布页，支持 {@code {版本}} 占位），
 * 没写才退回服务端 {@code latest.jar} 的直链。指向 GitHub 的好处是玩家能看到更新说明、
 * 也方便转到别的下载渠道；直链只适合「服主自建、不想公开仓库」的场景。</p>
 *
 * <p><b>为什么不能全自动：</b>Minecraft 的 mod 在启动阶段完成类加载与注册表冻结，
 * 运行期无法热替换正在使用的 jar。所以这里只做「检查 + 提示 + 一键跳转」，
 * 玩家替换 jar 后重启才生效。真正的无感更新必须由启动器在启动前完成。</p>
 */
public final class ModUpdater {
    private static final Logger LOG = LogManager.getLogger("nebula");
    private static final String MOD_ID = "nebula_bridge";
    /** 插件声明没写「端口」时的兜底值；真正生效的端口由 资源/客户端/*.json 决定。 */
    private static final int DEFAULT_PORT = 25568;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static boolean checked = false;
    private static boolean checking = false;
    /** 声明迟迟不到时只记一条日志，别每 tick 刷屏。 */
    private static boolean warnedMissingDecl = false;

    private ModUpdater() {
    }

    /** 换服后允许再检查一次；由 NebulaClient 在玩家进服时调用。 */
    static void reset() {
        checked = false;
        warnedMissingDecl = false;
    }

    static void tick() {
        if (checked || checking) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            return;
        }
        // 开关 / 端口 / 文案都由词库插件的 资源/客户端/*.json 决定；没有声明就完全不检查。
        JsonObject decl = ClientDecls.updater();
        if (decl == null) {
            // 声明可能还没随资源包到达；不置 checked，下一 tick 再试。
            if (!warnedMissingDecl) {
                warnedMissingDecl = true;
                LOG.info("[星云] 更新检查待命：资源包里的「更新提示」声明还没到，继续等");
            }
            return;
        }
        // 还没拿到连接地址（正在断开/切服）时也别锁死检查，下一 tick 再说。
        String host = hostOf(server.ip);
        if (host.isEmpty()) {
            return;
        }
        checked = true;
        int port = (int) ClientDecls.num(decl, "端口", DEFAULT_PORT);
        checking = true;
        String url = "http://" + host + ":" + port + "/nebula/mod/manifest.json";
        LOG.info("[星云] 更新检查：GET {}", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    checking = false;
                    if (response.statusCode() == 200) {
                        onManifest(minecraft, response.body(), decl, host, port);
                    } else {
                        LOG.warn("[星云] 更新检查失败：HTTP {}（{}）", response.statusCode(), url);
                    }
                })
                .exceptionally(error -> {
                    checking = false;
                    LOG.warn("[星云] 更新检查失败：连不上 {} 的 HTTP 服务（端口没放通？）：{}",
                            host, error.toString());
                    return null;
                });
    }

    private static void onManifest(Minecraft minecraft, String json, JsonObject decl, String host, int port) {
        String remote = jsonString(json, "version");
        String local = localVersion();
        // 点击目标：声明了 GitHub 就用发布页（支持 {版本} 占位），否则退回服务端直链。
        String target = githubUrl(decl, remote);
        if (target.isEmpty()) {
            target = downloadUrl(jsonString(json, "url"), host, port);
        }
        if (remote.isEmpty() || target.isEmpty() || local.isEmpty()) {
            LOG.warn("[星云] 更新检查：manifest 不完整（服务端版本 '{}'，本地 '{}'）", remote, local);
            return;
        }
        LOG.info("[星云] 更新检查：服务端 {}，本地 {}，点击目标 {}", remote, local, target);
        if (local.equals(remote)) {
            return;
        }
        // 文案模板由插件声明；{版本} / {本地} 在这里填值，mod 不写死任何提示语。
        String text = ClientDecls.str(decl, "文案", "")
                .replace("{版本}", remote)
                .replace("{本地}", local);
        if (text.isEmpty()) {
            return;
        }
        String click = target;
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                Component message = Component.literal(text)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent.OpenUrl(URI.create(click))));
                minecraft.player.displayClientMessage(message, false);
            }
        });
    }

    /**
     * 声明里的 {@code GitHub} → 可点击地址；没写返回空串。
     *
     * <p>{@code {版本}} 占位会换成服务端版本，配合 {@code releases/tag/v{版本}} 可精确落到
     * 该版本的发布页；写 {@code releases/latest} 则永远跟最新。</p>
     */
    private static String githubUrl(JsonObject decl, String remote) {
        String raw = ClientDecls.str(decl, "GitHub", "");
        if (raw.isBlank()) {
            return "";
        }
        String url = raw.replace("{版本}", remote == null ? "" : remote);
        try {
            URI.create(url);
        } catch (RuntimeException e) {
            LOG.warn("[星云] 更新检查：声明里的 GitHub 地址不合法（{}），改用服务端直链", url);
            return "";
        }
        return url;
    }

    private static String localVersion() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 下载链接：服务端猜出来的私网地址（192.168.x.x 之类）如果和我连服用的不是同一条路，
     * 就换回我自己这条 —— 否则点开就是别人家局域网里的死链。
     *
     * <p>manifest 里的 host 由服务端生成：没配 {@code host=} 时它只能猜本机第一个私网 IPv4，
     * 而玩家可能是从公网域名、frp 隧道或 {@code localhost} 进来的。服务端显式配了对外地址
     * 时（域名 / 公网 IP，非私网）则原样保留，不要去破坏 NAT、frp 的端口映射。
     * 判定为私网时用「我拉 manifest 用的 host + 端口」重拼，那条路刚刚成功过，必然可达。</p>
     */
    private static String downloadUrl(String declared, String host, int port) {
        String path = "/nebula/mod/latest.jar";
        String declaredHost = "";
        try {
            if (!declared.isEmpty()) {
                URI uri = URI.create(declared);
                String raw = uri.getRawPath();
                if (raw != null && raw.startsWith("/")) {
                    path = raw;
                }
                declaredHost = uri.getHost() == null ? "" : uri.getHost();
            }
        } catch (RuntimeException ignored) {
            // 链接坏了就退回约定路径 + 本机地址
        }
        if (!declaredHost.isEmpty() && !declaredHost.equalsIgnoreCase(host) && !isPrivate(declaredHost)) {
            return declared;
        }
        return "http://" + host + ":" + port + path;
    }

    /** 私网 / 回环 / IPv6 字面量：这些地址只在服务端自己那侧有意义，换服地址时一律不当权威。 */
    private static boolean isPrivate(String host) {
        if (host.indexOf(':') >= 0 || host.startsWith("[")) {
            return true;
        }
        if (host.equalsIgnoreCase("localhost") || host.startsWith("127.")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.startsWith("169.254.") || host.startsWith("0.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    // 不是数字就当域名
                }
            }
        }
        return false;
    }

    /** 从 {@code host:port} / {@code [::1]:port} 里取 host（http 服务端口与游戏端口不同）。 */
    private static String hostOf(String ip) {
        if (ip == null) {
            return "";
        }
        String text = ip.trim();
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            return close > 0 ? text.substring(0, close + 1) : text;
        }
        int first = text.indexOf(':');
        int last = text.lastIndexOf(':');
        if (first > 0 && first != last) {
            // 没带方括号的裸 IPv6，原样加括号返回
            return "[" + text + "]";
        }
        return first > 0 ? text.substring(0, first) : text;
    }

    /** 极简 JSON 字符串取值：找 {@code "key":"value"}。 */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) {
            return "";
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return "";
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return "";
        }
        return json.substring(firstQuote + 1, secondQuote);
    }
}
