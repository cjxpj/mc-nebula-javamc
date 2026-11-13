package nebula.neoforge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.neoforged.fml.loading.FMLPaths;

/** JNI 声明与原生库加载。 */
public final class NativeBridge {
    private static boolean loaded;

    private NativeBridge() {
    }

    /** 从 jar 内解压原生库并加载；可重复调用。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        String fileName = System.mapLibraryName("nebula_jni");
        String resource = "/META-INF/native/" + platform() + "/" + fileName;
        Path target = FMLPaths.CONFIGDIR.get()
                .resolve("nebula-bridge")
                .resolve("native")
                .resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = NativeBridge.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("jar 内缺少原生库：" + resource);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(target.toAbsolutePath().toString());
            loaded = true;
        } catch (Exception e) {
            throw new IllegalStateException("加载原生库失败：" + target, e);
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String archName = arch.contains("aarch64") ? "aarch64" : "x86_64";
        return osName + "-" + archName;
    }

    /** 创建引擎，返回句柄；失败返回 0。 */
    public static native long create(String wordlibRoot);

    /** 销毁引擎。 */
    public static native void destroy(long handle);

    /** 扫描并加载全部插件，返回 JSON。 */
    public static native String loadAll(long handle);

    /** 加载单个插件或 --all，返回 JSON。 */
    public static native String load(long handle, String target);

    /** 重载单个插件或 --all，返回 JSON。 */
    public static native String reload(long handle, String target);

    /** 开启调试（词库文件改动后自动重编译），返回 JSON。 */
    public static native String debug(long handle, String target);

    /** 关闭调试自动重编译，返回 JSON。 */
    public static native String undebug(long handle, String target);

    /** 卸载单个插件或 --all，返回 JSON。 */
    public static native String unload(long handle, String target);

    /** 列出插件状态，返回 JSON。 */
    public static native String list(long handle);

    /** 分发事件，返回 JSON（outputs / actions / cancel）。 */
    public static native String dispatch(long handle, String event, String payloadJson);

    /** 词库订阅的全部事件名（JSON 数组），供 EventBridge 按需注册 NeoForge 监听器。 */
    public static native String eventTriggers(long handle);

    /**
     * 按指令名分发：只匹配 {@code [指令]商店|shop}；没有对应词条时返回 {@code ok=false}。
     */
    public static native String dispatchCommand(long handle, String plugin, String command, String payloadJson);

    /** 点击界面对象：调用 {@code $世界.打开界面} 存下的闭包，payload 作为 {@code %参数1%}。 */
    public static native String clickGui(long handle, long guiId, String payloadJson);

    /** 界面关闭：丢掉闭包与捕获变量。 */
    public static native void closeGui(long handle, long guiId);
}
