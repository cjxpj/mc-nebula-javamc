package nebula.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 随服携带的离线文档站点：{@code <gameDir>/nebula/_docs/}。
 *
 * <p>文档不是词库：目录名以下划线开头，引擎 {@code scan_plugins} 会直接跳过（规则见
 * {@code bridge/src/lib.rs}），既不编译也不执行，纯粹给服主用浏览器打开看。</p>
 *
 * <p>内容来自 jar 内的 {@code docs/}（构建时由 {@code build.ps1} 从 {@code NebulaLoadMod/docs}
 * 拷入 md + html）。开服时先建 {@code nebula/}，再按 {@code docs/.stamp} 的内容指纹决定
 * 是否重建站点：首次（nebula 目录刚被创建）一定解压，之后只有 jar 里的文档真变了才整体
 * 覆盖，避免站内文件悄悄过期，也避免每次启动都重写磁盘。</p>
 *
 * <p>注意判据是「jar 里的指纹 vs 磁盘上的指纹」，不是文件时间：手动改 {@code _docs}
 * 里的文件不会自动纠正（当作本地改动保留）。想强制回到出厂内容，删掉 {@code _docs}
 * 或其中的 {@code .stamp}，下次启动就会重建。指纹在解压最后一步才写入，所以中途崩服
 * 留下的半套文件不会被认为是最新的。</p>
 *
 * <p>本类刻意不引用任何 Minecraft 类型：可以用
 * {@code java -cp <jar> 自检} 的方式在服务端之外单独验证解压结果。</p>
 */
final class DocsSite {

    /** jar 内文档资源的前缀。 */
    static final String RESOURCE_ROOT = "docs/";
    /** 内容指纹文件名，构建侧与运行侧同名。 */
    private static final String STAMP = ".stamp";
    /** 词库根目录名，与 {@code NebulaMod} 传给引擎的路径一致。 */
    private static final String ROOT = "nebula";
    /** 站点子目录名：下划线前缀 = 引擎跳过。 */
    private static final String SITE = "_docs";

    private DocsSite() {
    }

    /**
     * 确保磁盘上的文档站点与 jar 内资源一致。
     *
     * @param gameDir 服务端运行目录（{@code FMLPaths.GAMEDIR}）
     * @return 本次写入的说明；已是最新或没有文档资源时返回 {@code null}
     */
    static String ensure(Path gameDir) throws IOException {
        Path root = gameDir.resolve(ROOT);
        Path target = root.resolve(SITE);
        Files.createDirectories(root);

        Path artifact = ownArtifact();
        String expected = readStamp(artifact);
        if (expected == null || expected.isEmpty()) {
            // 开发态（classes 目录里没拷 docs）或旧 jar：安静跳过，别在日志里制造噪音。
            return null;
        }
        Path stampFile = target.resolve(STAMP);
        if (Files.isRegularFile(stampFile)
                && expected.equals(Files.readString(stampFile, StandardCharsets.UTF_8).trim())) {
            return null;
        }

        deleteTree(target);
        int files = Files.isDirectory(artifact)
                ? copyFromDirectory(artifact, target)
                : copyFromJar(artifact, target);
        Files.createDirectories(target);
        Files.writeString(stampFile, expected, StandardCharsets.UTF_8);
        return "离线文档站点已生成：" + target + "（" + files + " 个文件）";
    }

    /** 自身所在的 jar（或开发态下的 classes 目录）；取不到就当作「没有文档」。 */
    private static Path ownArtifact() {
        try {
            return Path.of(DocsSite.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStamp(Path artifact) throws IOException {
        if (artifact == null) {
            return null;
        }
        if (Files.isDirectory(artifact)) {
            Path stamp = artifact.resolve(RESOURCE_ROOT + STAMP);
            return Files.isRegularFile(stamp)
                    ? Files.readString(stamp, StandardCharsets.UTF_8).trim()
                    : null;
        }
        if (!Files.isRegularFile(artifact)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            ZipEntry entry = zip.getEntry(RESOURCE_ROOT + STAMP);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
    }

    private static int copyFromJar(Path jar, Path target) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(RESOURCE_ROOT)) {
                    continue;
                }
                String rel = name.substring(RESOURCE_ROOT.length());
                if (rel.isEmpty() || rel.equals(STAMP)) {
                    continue;
                }
                Path dest = target.resolve(rel);
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        return count;
    }

    /** 开发态：从 {@code build/classes/docs} 直接拷，省掉每次都要打包 jar。 */
    private static int copyFromDirectory(Path artifact, Path target) throws IOException {
        Path docsRoot = artifact.resolve(RESOURCE_ROOT);
        int count = 0;
        try (Stream<Path> files = Files.walk(docsRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path rel = docsRoot.relativize(file);
                if (rel.toString().replace('\\', '/').equals(STAMP)) {
                    continue;
                }
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
