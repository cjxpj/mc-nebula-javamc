package nebula.neoforge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 读取 jar 内的 {@code nebula/world-specs.json}：{@code 世界} 方法的唯一声明来源。
 * 规格是纯数据，改方法清单不需要重编 Rust dll，只需重新打 jar。
 */
public final class WorldSpecs {
    private static final String RESOURCE = "/nebula/world-specs.json";

    private WorldSpecs() {
    }

    public static String load() {
        try (InputStream in = WorldSpecs.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("jar 内缺少 " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + RESOURCE + " 失败", e);
        }
    }
}
