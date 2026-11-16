package nebula.neoforge.snippet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 用 JDK 自带的 javac 在内存里编译一段 Java 源码。
 *
 * <p>classpath 是这样来的：</p>
 * <ol>
 *   <li>本 mod 的 jar（片段要 {@code import nebula.neoforge.snippet.Ctx}）；</li>
 *   <li>{@code MinecraftServer} 所在的 jar（原版 + NeoForge patch，官方命名）；</li>
 *   <li>{@code <gameDir>/libraries} 下全部 jar（Brigadier、DataFixerUpper、Gson 等）；</li>
 *   <li>JVM 自己的 {@code java.class.path}，兜底。</li>
 * </ol>
 *
 * <p>第 2 步用 {@link CodeSource} 精确取，不猜路径：NeoForge 1.21 的
 * {@code minecraft-server-patched} jar 本身就是官方命名（重映射在安装期完成），
 * 所以磁盘上的类与运行时看到的是同一套签名。</p>
 *
 * <p>输出全部落在内存里，由 {@link SnippetClassLoader} 定义，不写磁盘、不留临时文件。</p>
 */
final class SnippetCompiler {

    /** 编译失败；message 是给人看的逐条错误（含行号）。 */
    static final class SnippetError extends Exception {
        SnippetError(String message) {
            super(message);
        }
    }

    /** 编译产物：主类名 + 全部 class 字节（含内部类 / lambda 生成的类）。 */
    static final class Compiled {
        final String mainClass;
        final Map<String, byte[]> classes;

        Compiled(String mainClass, Map<String, byte[]> classes) {
            this.mainClass = mainClass;
            this.classes = classes;
        }
    }

    private static final int MAX_LIBRARY_JARS = 2_000;

    private static final Logger LOG = LogManager.getLogger("nebula");

    /** classpath 只算一次：扫 libraries 目录要几十毫秒，不值得每次编译都做。 */
    private static volatile String classPath;

    private SnippetCompiler() {
    }

    static Compiled compile(String mainClass, String source) throws SnippetError {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new SnippetError("当前 JVM 没有 javac（缺 jdk.compiler 模块）：$java.eval 需要 JDK 启动服务器，"
                    + "JRE / jlink 精简运行时不可用");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard =
                javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        Map<String, ByteArrayOutputStream> buffers = new HashMap<>();
        MemoryFileManager manager = new MemoryFileManager(standard, buffers);
        StringWriter console = new StringWriter();
        boolean ok;
        try {
            List<String> options = new ArrayList<>(List.of(
                    "-proc:none",          // 不跑注解处理器：省时间，也避免加载未知处理器
                    "-nowarn",
                    "-g:lines",            // 只要行号：栈追踪够用，class 文件小
                    "-Xlint:none",
                    "-encoding", "UTF-8"   // 源码含中文时 Windows 默认 GBK 会乱码
            ));
            // 空串会被 javac 当成非法标志；真扫不到 jar 时干脆不传，让「找不到符号」自己说话。
            String path = classPath();
            if (!path.isEmpty()) {
                options.add("-classpath");
                options.add(path);
            }
            JavaCompiler.CompilationTask task = javac.getTask(
                    new PrintWriter(console), manager, diagnostics, options, null,
                    List.of(new SourceUnit(mainClass, source)));
            ok = task.call();
        } catch (RuntimeException e) {
            throw new SnippetError("编译器内部错误：" + e);
        } catch (Error e) {
            throw new SnippetError("编译器无法加载：" + e);
        } finally {
            try {
                manager.close();
            } catch (IOException | RuntimeException ignored) {
                // 关闭失败不影响已产出的字节
            }
        }

        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(format(diagnostic));
            }
        }
        if (!ok || !errors.isEmpty()) {
            String hint = console.toString().trim();
            if (errors.isEmpty()) {
                errors.add(hint.isEmpty() ? "编译失败（没有诊断信息）" : hint);
            }
            throw new SnippetError(String.join("\n", errors));
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        buffers.forEach((name, buffer) -> classes.put(name, buffer.toByteArray()));
        if (!classes.containsKey(mainClass)) {
            throw new SnippetError("没有找到主类 " + mainClass + "，请检查 class 名是否正确");
        }
        return new Compiled(mainClass, classes);
    }

    // ────────────────────────── classpath ──────────────────────────

    private static String classPath() {
        String cached = classPath;
        if (cached != null) {
            return cached;
        }
        synchronized (SnippetCompiler.class) {
            if (classPath == null) {
                classPath = buildClassPath();
            }
            return classPath;
        }
    }

    private static String buildClassPath() {
        long started = System.nanoTime();
        Set<String> jars = new LinkedHashSet<>();
        addCodeSource(jars, SnippetCompiler.class);
        addCodeSource(jars, MinecraftServer.class);
        scanLibraries(jars);
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            addIfJar(jars, entry);
        }
        // 第一次 eval 会冻结一下：扫 jar 目录 + javac 首次加载。打出来，免得以后有人
        // 只看到「卡了两秒」却在日志里找不到任何线索。
        LOG.info("[星云] $java.eval classpath 就绪：{} 个 jar，{} ms",
                jars.size(), (System.nanoTime() - started) / 1_000_000);
        return String.join(File.pathSeparator, jars);
    }

    private static void addCodeSource(Set<String> jars, Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                addIfJar(jars, new File(source.getLocation().toURI()).getAbsolutePath());
            }
        } catch (Exception ignored) {
            // 拿不到就靠 libraries 扫描兜底
        }
    }

    /** {@code <gameDir>/libraries} 下全部 jar；客户端与服务端的安装布局都是这个位置。 */
    private static void scanLibraries(Set<String> jars) {
        Path gameDir;
        try {
            gameDir = FMLPaths.GAMEDIR.get();
        } catch (Throwable t) {
            return;
        }
        Path libraries = gameDir.resolve("libraries");
        if (!Files.isDirectory(libraries)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(libraries, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .limit(MAX_LIBRARY_JARS)
                    .forEach(path -> jars.add(path.toAbsolutePath().toString()));
        } catch (IOException | RuntimeException ignored) {
            // 扫不动就用已经收集到的
        }
    }

    private static void addIfJar(Set<String> jars, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String path = raw.trim();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return;
        }
        File file = new File(path);
        if (file.isFile()) {
            jars.add(file.getAbsolutePath());
        }
    }

    // ────────────────────────── 源码 / 字节码载体 ──────────────────────────

    private static String format(Diagnostic<? extends JavaFileObject> diagnostic) {
        String message = diagnostic.getMessage(Locale.ROOT);
        long line = diagnostic.getLineNumber();
        return line > 0 ? "第 " + line + " 行：" + message : message;
    }

    private static final class SourceUnit extends SimpleJavaFileObject {
        private final String code;

        SourceUnit(String mainClass, String code) {
            super(URI.create("string:///" + mainClass.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class OutputUnit extends SimpleJavaFileObject {
        private final ByteArrayOutputStream buffer;

        OutputUnit(String className, ByteArrayOutputStream buffer) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.CLASS.extension),
                    Kind.CLASS);
            this.buffer = buffer;
        }

        @Override
        public OutputStream openOutputStream() {
            return buffer;
        }
    }

    /** 只做一件事：把 class 输出接到内存流上。输入（classpath）交给 javac 自己读。 */
    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> buffers;

        MemoryFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> buffers) {
            super(delegate);
            this.buffers = buffers;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            return new OutputUnit(className,
                    buffers.computeIfAbsent(className, key -> new ByteArrayOutputStream()));
        }
    }
}
