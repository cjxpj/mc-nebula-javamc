package nebula.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * {@code 世界} 查询与动作共用的宿主侧支撑。
 *
 * <p>两层考虑：</p>
 * <ul>
 *   <li>大量动作走原版命令，不需要碰版本相关的类；查询也尽量只用稳定的公开 API。</li>
 *   <li>少数官方未暴露公开 getter 的方法（游戏规则 / 属性注册表等）统一走反射，
 *       读不到就返回空串，不让一个签名变动拖垮整个 mod 的编译。</li>
 * </ul>
 */
final class McSupport {
    private McSupport() {
    }

    // ────────────────────────── 世界 / 玩家 / 实体 ──────────────────────────

    static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    static ServerLevel level(MinecraftServer server, String dimension) {
        if (server == null || dimension == null || dimension.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(dimension.trim());
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** 主世界；时间 / 难度 / 出生点这类全局状态都读它。 */
    static ServerLevel overworld(MinecraftServer server) {
        Object level = invoke(server, "overworld");
        return level instanceof ServerLevel l ? l : level(server, "minecraft:overworld");
    }

    static ServerPlayer player(MinecraftServer server, String nameOrUuid) {
        if (server == null || nameOrUuid == null || nameOrUuid.isEmpty()) {
            return null;
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(nameOrUuid);
        if (player != null) {
            return player;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(nameOrUuid.trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    static Item item(String id) {
        Identifier key = Identifier.tryParse(id == null ? "" : id.trim());
        if (key == null) {
            return Items.AIR;
        }
        // 1.21.11 起 Registry#get(Identifier) 返回 Optional<Holder.Reference<T>>，
        // 取实际注册对象用 getOptional。
        return BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
    }

    /** 按实体 id / UUID / 名字找实体；找不到返回 null。 */
    static Entity entity(MinecraftServer server, String raw) {
        if (server == null || raw == null || raw.isBlank()) {
            return null;
        }
        ServerPlayer byName = player(server, raw.trim());
        if (byName != null) {
            return byName;
        }
        int id = -1;
        try {
            id = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            // 不是数字 id
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(raw.trim());
        } catch (Exception ignored) {
            // 不是 UUID
        }
        Object levels = invoke(server, "getAllLevels");
        if (!(levels instanceof Iterable<?> iterable)) {
            return null;
        }
        for (Object levelObject : iterable) {
            if (!(levelObject instanceof ServerLevel level)) {
                continue;
            }
            if (id >= 0) {
                Object found = invoke1(level, "getEntity", int.class, id);
                if (found instanceof Entity entity) {
                    return entity;
                }
            }
            for (Object candidate : allEntities(level)) {
                if (!(candidate instanceof Entity entity)) {
                    continue;
                }
                if (uuid != null && uuid.equals(entity.getUUID())) {
                    return entity;
                }
                String shown = entity.getName().getString();
                String wanted = raw.trim();
                // 1.21.11 的 /summon 会把 CustomName:'"TestCow"' 解析成带引号的文本，
                // 名字匹配同时接受剥掉一层引号的形式（不带引号的裸名字不受影响）。
                if (shown.equals(wanted)
                        || (shown.length() >= 2 && shown.startsWith("\"") && shown.endsWith("\"")
                        && shown.substring(1, shown.length() - 1).equals(wanted))) {
                    return entity;
                }
            }
        }
        return null;
    }

    /** 命令里的实体选择器统一用 UUID，避免数字 id / 中文名带来的解析歧义。 */
    static String selector(MinecraftServer server, String raw) {
        Entity entity = entity(server, raw);
        return entity == null ? null : entity.getUUID().toString();
    }

    static Iterable<?> allEntities(ServerLevel level) {
        Object all = invoke(level, "getAllEntities");
        if (!(all instanceof Iterable<?> iterable)) {
            Object getter = invoke(level, "getEntities");
            all = invoke(getter, "getAll");
        }
        return all instanceof Iterable<?> iterable ? iterable : java.util.List.of();
    }

    static JsonObject itemJson(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            return json;
        }
        json.addProperty("名称", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("数量", stack.getCount());
        json.addProperty("显示名", stack.getDisplayName().getString());
        json.addProperty("耐久", stack.getDamageValue());
        json.addProperty("最大耐久", stack.getMaxDamage());
        return json;
    }

    static JsonObject entityJson(Entity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entity.getId());
        json.addProperty("uuid", entity.getUUID().toString());
        json.addProperty("名称", entity.getName().getString());
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        json.addProperty("类型", type == null ? "" : type.toString());
        json.addProperty("维度", entity.level().dimension().identifier().toString());
        json.addProperty("x", entity.getX());
        json.addProperty("y", entity.getY());
        json.addProperty("z", entity.getZ());
        if (entity instanceof LivingEntity living) {
            json.addProperty("血量", living.getHealth());
            json.addProperty("最大血量", living.getMaxHealth());
        }
        return json;
    }

    // ────────────────────────── 反射兜底 ──────────────────────────

    /** 找公开方法（含父类），找不到时再找声明的私有 / 保护方法。 */
    private static Method method(Class<?> type, String name, Class<?>... params)
            throws NoSuchMethodException {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException missing) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Method found = current.getDeclaredMethod(name, params);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            throw missing;
        }
    }

    static Object invoke(Object target, String name) {
        return invokeArgs(target, name, new Class<?>[0]);
    }

    static Object invoke1(Object target, String name, Class<?> paramType, Object arg) {
        return invokeArgs(target, name, new Class<?>[]{paramType}, arg);
    }

    static Object invokeArgs(Object target, String name, Class<?>[] params, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            return method(target.getClass(), name, params).invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 依次尝试若干方法名，返回第一个成功的结果。 */
    static Object tryInvoke(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Object value = invoke(target, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    static boolean setField(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Exception ignored) {
                current = current.getSuperclass();
            }
        }
        return false;
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String normalized(String raw) {
        return raw == null ? "" : raw.replace("_", "").replace(".", "").replace(":", "").toLowerCase(Locale.ROOT);
    }

    // ────────────────────────── JSON 取值 ──────────────────────────

    static String str(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    static int integer(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static double number(JsonObject json, String key, double fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static boolean bool(JsonObject json, String key, boolean fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static JsonArray array() {
        return new JsonArray();
    }

    // ────────────────────────── tick 统计 ──────────────────────────

    private static final int WINDOW = 40;
    private static final ArrayDeque<Long> INTERVALS = new ArrayDeque<>();
    private static final ArrayDeque<Long> COSTS = new ArrayDeque<>();
    private static long lastTickNanos = 0L;
    private static long tickStartNanos = 0L;
    private static long ticks = 0L;

    static synchronized void onTickPre() {
        tickStartNanos = System.nanoTime();
    }

    static synchronized void onTickPost() {
        long now = System.nanoTime();
        if (tickStartNanos > 0L) {
            push(COSTS, now - tickStartNanos);
        }
        if (lastTickNanos > 0L) {
            push(INTERVALS, now - lastTickNanos);
        }
        lastTickNanos = now;
        ticks++;
    }

    private static void push(ArrayDeque<Long> queue, long value) {
        queue.addLast(value);
        while (queue.size() > WINDOW) {
            queue.removeFirst();
        }
    }

    private static double average(ArrayDeque<Long> queue) {
        if (queue.isEmpty()) {
            return 0.0;
        }
        long total = 0L;
        for (long value : queue) {
            total += value;
        }
        return (double) total / queue.size();
    }

    /** 最近 40 tick 的平均间隔换算成 TPS，正常服务端封顶 20。 */
    static synchronized String tps() {
        double millis = average(INTERVALS) / 1_000_000.0;
        double value = millis > 0.0 ? Math.min(20.0, 1000.0 / millis) : 20.0;
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Pre → Post 的耗时，近似每 tick 的服务端开销。 */
    static synchronized String mspt() {
        return String.format(Locale.ROOT, "%.2f", average(COSTS) / 1_000_000.0);
    }

    static synchronized String tickCount() {
        return String.valueOf(ticks);
    }

    // ────────────────────────── 环境变量 / 本地时间 ──────────────────────────

    /** 词库设置的环境变量只作用于桥接层自己的调度，不改 JVM 进程环境。 */
    private static final Map<String, String> ENV = new ConcurrentHashMap<>();

    static String env(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String override = ENV.get(name);
        return override != null ? override : System.getenv(name);
    }

    static void setEnv(String name, String value) {
        if (name != null && !name.isBlank()) {
            ENV.put(name, value == null ? "" : value);
        }
    }

    static String localTime() {
        return LocalDateTime.now().toString();
    }

    /** 把 Stream / Optional 包住的结果统一摊平成文本，用于标签这类集合返回值。 */
    static String join(Object value, String separator) {
        if (value == null) {
            return "";
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? text(optional.get()) : "";
        }
        if (value instanceof Stream<?> stream) {
            StringBuilder out = new StringBuilder();
            stream.forEach(item -> {
                if (out.length() > 0) {
                    out.append(separator);
                }
                out.append(text(invoke(item, "location")));
            });
            return out.toString();
        }
        return text(value);
    }

    /**
     * 1.21.11 起 Inventory 的 items / armor / offhand 三个 NonNullList 全部变为私有。
     * 这里按槽位号统一暴露可写视图：0-35 主背包、36-39 护甲、40 副手、41+ 新增装备槽。
     * set() 落到 Inventory#setItem，所以扣物品 / 清空背包会自动覆盖 1.21.11 的新槽位。
     */
    public static List<ItemStack> inventorySlots(Inventory inventory) {
        return new AbstractList<>() {
            @Override public ItemStack get(int index) { return inventory.getItem(index); }
            @Override public ItemStack set(int index, ItemStack value) {
                ItemStack old = inventory.getItem(index);
                inventory.setItem(index, value);
                return old;
            }
            @Override public int size() { return inventory.getContainerSize(); }
        };
    }
}
