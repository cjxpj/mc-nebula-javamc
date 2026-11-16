package nebula.neoforge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * mod 自发布：把「当前正在运行的 jar」通过 {@link NebulaPack} 的 HTTP 服务对外提供。
 *
 * <p>客户端 mod 启动后向服务端请求 {@code /nebula/mod/manifest.json}，比对版本；
 * 版本落后时提示（或下载）新 jar。注意：<b>Minecraft 无法运行期热加载 mod</b>，
 * 所以这里只能做到「下载 + 提示重启」，真正的无感更新需要启动器配合。</p>
 */
public final class NebulaModDistributor {
    private static final Logger LOG = LogManager.getLogger("nebula");

    private static volatile byte[] jar;
    private static volatile String version = "";
    private static volatile String sha256 = "";
    private static volatile String fileName = "";

    private NebulaModDistributor() {
    }

    /** 开服时调用：定位自身 jar、读入内存、算哈希。返回日志摘要，失败返回 null。 */
    static String prepare() {
        jar = null;
        Path self = selfJar();
        if (self == null || !Files.isRegularFile(self)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(self);
            jar = bytes;
            fileName = self.getFileName().toString();
            sha256 = sha256Hex(bytes);
            version = localVersion();
            return "mod 更新源就绪：" + fileName + " · " + kib(bytes.length)
                    + " · sha256 " + sha256.substring(0, 8);
        } catch (Exception e) {
            LOG.warn("[星云] mod 更新源准备失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 定位当前 mod 的 jar。优先用 CodeSource（最直接），失败再退回 ModList 的 mod 文件路径。
     */
    private static Path selfJar() {
        try {
            java.security.CodeSource cs = NebulaMod.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path candidate = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 退回 ModList
        }
        try {
            var fileInfo = ModList.get().getModFileById(NebulaMod.MODID);
            if (fileInfo != null && fileInfo.getFile() != null) {
                Path candidate = fileInfo.getFile().getFilePath();
                if (candidate != null && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就当作未启用
        }
        return null;
    }

    private static String localVersion() {
        try {
            return ModList.get().getModContainerById(NebulaMod.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean available() {
        return jar != null;
    }

    /** 版本清单 JSON，给客户端比对用。 */
    static String manifestJson(String baseUrl) {
        byte[] data = jar;
        if (data == null) {
            return null;
        }
        String url = baseUrl + "/nebula/mod/latest.jar";
        return "{\"modId\":\"" + NebulaMod.MODID + "\","
                + "\"version\":\"" + version + "\","
                + "\"file\":\"" + fileName + "\","
                + "\"sha256\":\"" + sha256 + "\","
                + "\"url\":\"" + url + "\","
                + "\"size\":" + data.length + "}";
    }

    static byte[] jar() {
        return jar;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sum = digest.digest(data);
        StringBuilder sb = new StringBuilder(sum.length * 2);
        for (byte b : sum) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String kib(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    /** 供 NebulaPack 的 HTTP 处理器调用：返回 jar 字节数组（可能为 null）。 */
    static byte[] rawJar() {
        return jar;
    }

    static String rawManifest(String baseUrl) {
        String json = manifestJson(baseUrl);
        return json;
    }

    static String version() {
        return version;
    }

    static String sha256() {
        return sha256;
    }

    static byte[] bytes() {
        return jar;
    }

    static String name() {
        return fileName;
    }
}
