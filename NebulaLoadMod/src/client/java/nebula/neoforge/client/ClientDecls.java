package nebula.neoforge.client;

import java.io.BufferedReader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;

/**
 * 客户端声明读取器：所有客户端表现都由词库插件的 {@code 资源/客户端/*.json} 声明。
 *
 * <p>{@code NebulaPack} 会把这些文件打成 {@code assets/nebula/client/*.json} 随资源包下发，
 * 这里从 {@link Minecraft#getResourceManager()} 读出来合并成一份。客户端没有服务端的
 * {@code nebula/} 目录，资源包是唯一能到达玩家机器的通道。</p>
 *
 * <p>本类不含任何内容决策，只做「读 + 合并 + 查询」；没有声明时返回 {@code null}，
 * 让调用方什么都不做，而不是回退到 mod 里写死的外观 / 文案。</p>
 */
public final class ClientDecls {
    private static volatile JsonObject cached;

    private ClientDecls() {
    }

    /** 进服 / 资源包重载后清缓存，下一次查询重新读声明。 */
    public static synchronized void refresh() {
        cached = null;
    }

    /** 合并后的完整声明；读不到资源包时返回空对象（不抛异常，别拖垮渲染）。 */
    public static JsonObject all() {
        JsonObject hit = cached;
        if (hit != null) {
            return hit;
        }
        JsonObject merged = new JsonObject();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return merged;
        }
        var found = minecraft.getResourceManager().listResources("client",
                id -> "nebula".equals(id.getNamespace()) && id.getPath().endsWith(".json"));
        for (var entry : found.entrySet()) {
            try {
                Resource resource = entry.getValue();
                try (BufferedReader reader = resource.openAsReader()) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed != null && parsed.isJsonObject()) {
                        merge(merged, parsed.getAsJsonObject());
                    }
                }
            } catch (Exception ignored) {
                // 单个插件的声明坏了就跳过，别影响其它插件
            }
        }
        cached = merged;
        return merged;
    }

    /** 多份声明按目录顺序合并；对象递归合并，先写到的键优先。 */
    private static void merge(JsonObject target, JsonObject source) {
        for (var entry : source.entrySet()) {
            JsonElement value = entry.getValue();
            if (!target.has(entry.getKey())) {
                target.add(entry.getKey(), value);
                continue;
            }
            JsonElement old = target.get(entry.getKey());
            if (old.isJsonObject() && value.isJsonObject()) {
                merge(old.getAsJsonObject(), value.getAsJsonObject());
            }
        }
    }

    /** 某实体 id 的外观声明；没有声明返回 {@code null}（渲染器就不画）。 */
    public static JsonObject entityLook(String id) {
        JsonObject all = all();
        if (!all.has("实体外观") || !all.get("实体外观").isJsonObject()) {
            return null;
        }
        JsonObject looks = all.getAsJsonObject("实体外观");
        if (!looks.has(id) || !looks.get(id).isJsonObject()) {
            return null;
        }
        return looks.getAsJsonObject(id);
    }

    /** 更新提示声明；没有声明或开关为「否」时返回 {@code null}（默认不检查）。 */
    public static JsonObject updater() {
        JsonObject all = all();
        if (!all.has("更新提示") || !all.get("更新提示").isJsonObject()) {
            return null;
        }
        JsonObject decl = all.getAsJsonObject("更新提示");
        String on = str(decl, "开关", "否");
        if (!"是".equals(on) && !"true".equalsIgnoreCase(on)) {
            return null;
        }
        return decl;
    }

    public static String str(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    public static double num(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 二维量（如盖子的铰链 y/z，方块坐标 0..1），缺省用给定值。 */
    public static double[] pair(JsonObject object, String key, double fallbackA, double fallbackB) {
        double[] out = {fallbackA, fallbackB};
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return out;
        }
        JsonArray array = object.getAsJsonArray(key);
        for (int i = 0; i < Math.min(2, array.size()); i++) {
            try {
                out[i] = array.get(i).getAsDouble();
            } catch (RuntimeException ignored) {
                // 单个分量坏了就用缺省
            }
        }
        return out;
    }

    /** 三维偏移，缺省全 0。 */
    public static double[] offset(JsonObject look) {
        double[] out = {0, 0, 0};
        if (look == null || !look.has("偏移") || !look.get("偏移").isJsonArray()) {
            return out;
        }
        JsonArray array = look.getAsJsonArray("偏移");
        for (int i = 0; i < Math.min(3, array.size()); i++) {
            try {
                out[i] = array.get(i).getAsDouble();
            } catch (RuntimeException ignored) {
                // 单个分量坏了用 0
            }
        }
        return out;
    }
}
