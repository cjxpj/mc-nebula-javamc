package nebula.neoforge.snippet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@code $java.eval} 的落地：编译、缓存、实例化、执行、按插件归属回收。
 *
 * <p>缓存键是 {@code 插件名 + 源码 SHA-256}。源码不变就复用同一个对象——片段里的字段
 * 因此能跨调用保留状态；改一个字符就是新键、新对象，旧状态自然丢弃。这比额外加一个
 * {@code reset} 接口干净，也避免「改了代码但状态还是旧的」这类难查的问题。</p>
 *
 * <p>编译失败同样进缓存（错误文本一起存），否则一段写错的代码会在每个 tick 重编一次，
 * 直接把服务器拖垮。要重试就改源码（键变），或 {@code /nebula load} 清掉该插件。</p>
 */
public final class JavaSnippet {
    private static final Logger LOG = LogManager.getLogger("nebula");

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_ENTRIES = 128;

    private static final Pattern PUBLIC_CLASS_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*public[ \\t]+(?:(?:final|abstract|sealed|non-sealed|strictfp)[ \\t]+)*class[ \\t]+(\\w+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|final|abstract|sealed|non-sealed|strictfp|static)[ \\t]+)*class[ \\t]+(\\w+)");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^[ \\t]*package[ \\t]+([\\w.]+)[ \\t]*;");
    private static final Pattern RETURN_PATTERN = Pattern.compile("\\breturn\\b");

    private JavaSnippet() {
    }

    private static final class Entry {
        final String plugin;
        final String key;
        /** null 表示编译失败，错误在 {@link #error} 里。 */
        final NebulaSnippet snippet;
        final String error;
        volatile long used;

        Entry(String plugin, String key, NebulaSnippet snippet, String error) {
            this.plugin = plugin;
            this.key = key;
            this.snippet = snippet;
            this.error = error;
            this.used = System.nanoTime();
        }
    }

    /** 由 {@code McApi.query} 的 {@code java.eval} 分支调用。 */
    public static String eval(JsonObject request) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return "";
        }
        // 片段会直接碰世界对象。正常路径一定在主线程（Rust 在 dispatch 里同步回调），
        // 这里兜一道：真有人从别处调进来，宁可拒绝也不要留下难查的并发损坏。
        if (!server.isSameThread()) {
            LOG.warn("[星云] $java.eval 在非主线程被调用（{}），已拒绝", Thread.currentThread().getName());
            return "[java] 必须在服务端主线程执行";
        }
        String readError = str(request, "错误");
        if (!readError.isEmpty()) {
            warn(request, "读取失败：" + readError);
            return "[java] " + readError;
        }
        String code = str(request, "代码");
        if (code.isBlank()) {
            warn(request, "代码为空");
            return "[java] 代码为空";
        }
        String plugin = str(request, "插件");
        String source = str(request, "来源");
        String arg = str(request, "参数");

        String key = plugin + "|" + digest(code);
        Entry entry = CACHE.get(key);
        if (entry == null) {
            entry = build(plugin, key, source, code);
            CACHE.put(key, entry);
            trim();
        }
        entry.used = System.nanoTime();
        if (entry.snippet == null) {
            return entry.error;
        }
        Ctx ctx = new Ctx(server, plugin, source);
        try {
            String out = entry.snippet.run(ctx, arg);
            return out == null ? "" : out;
        } catch (Throwable t) {
            LOG.warn("[星云] $java.eval 运行异常（{}）", source, t);
            return "[java] 运行异常：" + t;
        }
    }

    /** 插件卸载 / 重编译时丢掉它的全部片段实例，避免旧类被引用着无法回收。 */
    public static void forget(String plugin) {
        if (plugin == null || plugin.isBlank()) {
            return;
        }
        String name = plugin.trim();
        // `--all` / `全部` 这类写法在 NebulaMod 那里已经分流，但这里是公开入口，
        // 直接调进来时也要认，否则一个片段都清不掉。
        if (name.startsWith("-") || name.equals("*") || name.equals("all")
                || name.equals("全部") || name.equals("所有")) {
            forgetAll();
            return;
        }
        boolean removed = CACHE.entrySet().removeIf(e -> e.getValue().plugin.equals(name));
        if (removed) {
            LOG.info("[星云] 已释放插件 {} 的 $java.eval 片段", name);
        }
    }

    /** 服务器停机时清空，等价于把片段对象的生命周期收回到进程内。 */
    public static void forgetAll() {
        if (!CACHE.isEmpty()) {
            LOG.info("[星云] 清空 {} 个 $java.eval 片段", CACHE.size());
            CACHE.clear();
        }
    }

    // ────────────────────────── 编译 / 实例化 ──────────────────────────

    private static Entry build(String plugin, String key, String source, String code) {
        try {
            Prepared prepared = prepare(code);
            ClassLoader resolver = JavaSnippet.class.getClassLoader();
            SnippetCompiler.Compiled compiled = SnippetCompiler.compile(prepared.className, prepared.source);
            SnippetClassLoader loader = new SnippetClassLoader(resolver, compiled.classes);
            Class<?> type = Class.forName(compiled.mainClass, true, loader);
            NebulaSnippet snippet = instantiate(type);
            LOG.info("[星云] $java.eval 编译成功：{}（{} 个类，来源 {}）",
                    compiled.mainClass, compiled.classes.size(), source);
            return new Entry(plugin, key, snippet, null);
        } catch (SnippetCompiler.SnippetError e) {
            LOG.warn("[星云] $java.eval 编译失败（{}）：\n{}", source, e.getMessage());
            return new Entry(plugin, key, null, "[java] 编译失败：" + e.getMessage());
        } catch (Throwable t) {
            LOG.warn("[星云] $java.eval 初始化失败（{}）", source, t);
            return new Entry(plugin, key, null, "[java] 初始化失败：" + t);
        }
    }

    private static final class Prepared {
        final String className;
        final String source;

        Prepared(String className, String source) {
            this.className = className;
            this.source = source;
        }
    }

    /**
     * 两种写法：写了 {@code class} 就当完整类（可以有字段、有状态），否则补全成
     * {@code NebulaSnippet} 的 {@code run} 方法体。
     */
    private static Prepared prepare(String code) {
        // 先找 public 类：文件里常有一个辅助类排在前面，抓错主类的话 javac 会报
        // 「class X is public, should be declared in X.java」。
        String simple = null;
        Matcher matcher = PUBLIC_CLASS_PATTERN.matcher(code);
        if (matcher.find()) {
            simple = matcher.group(1);
        } else {
            matcher = CLASS_PATTERN.matcher(code);
            if (matcher.find()) {
                simple = matcher.group(1);
            }
        }
        if (simple != null) {
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(code);
            String name = packageMatcher.find() ? packageMatcher.group(1) + "." + simple : simple;
            return new Prepared(name, code);
        }
        simple = "Snip" + digest(code).substring(0, 12);
        String className = "nebula.snippet.gen." + simple;
        StringBuilder source = new StringBuilder();
        source.append("package nebula.snippet.gen;\n\n");
        source.append("public final class ").append(simple)
                .append(" implements nebula.neoforge.snippet.NebulaSnippet {\n");
        source.append("    @Override\n");
        source.append("    public String run(nebula.neoforge.snippet.Ctx ctx, String arg) throws Exception {\n");
        // 包一层作用域块：片段里声明的局部变量不会和下面补的 return 冲突。
        source.append("        {\n").append(code).append("\n        }\n");
        if (!RETURN_PATTERN.matcher(code).find()) {
            source.append("        return \"\";\n");
        }
        source.append("    }\n}\n");
        return new Prepared(className, source.toString());
    }

    private static NebulaSnippet instantiate(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        if (instance instanceof NebulaSnippet snippet) {
            return snippet;
        }
        // 兼容不写 implements 的完整类：只要有一个 public String run(Ctx, String) 就当成入口。
        Method entry = null;
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("run") && parameters.length == 2
                    && parameters[0] == Ctx.class && parameters[1] == String.class) {
                entry = method;
                break;
            }
        }
        if (entry == null) {
            throw new IllegalStateException("类 " + type.getName()
                    + " 既没有实现 NebulaSnippet，也没有 public String run(Ctx, String)");
        }
        Method target = entry;
        Object owner = instance;
        return (ctx, arg) -> {
            try {
                Object out = target.invoke(owner, ctx, arg);
                return out == null ? "" : String.valueOf(out);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(cause);
            }
        };
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static void warn(JsonObject request, String message) {
        LOG.warn("[星云] $java.eval {}（插件 {}，来源 {}）",
                message, str(request, "插件"), str(request, "来源"));
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String digest(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = sha.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static void trim() {
        if (CACHE.size() <= MAX_ENTRIES) {
            return;
        }
        Map<String, Long> used = new HashMap<>();
        CACHE.forEach((key, entry) -> used.put(key, entry.used));
        used.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(CACHE.size() - MAX_ENTRIES)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(CACHE::remove);
    }
}
