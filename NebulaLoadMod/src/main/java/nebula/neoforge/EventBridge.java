package nebula.neoforge;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 通用 NeoForge 事件桥：把整个事件总线按「订阅」暴露给词库。
 *
 * <p>jar 内的 {@code nebula/events.json} 是构建期从 NeoForge universal jar 抽取的事件
 * 类清单（当前 267 条，来自 NeoForge 21.11.42）。宿主不会把它们全部挂上去——只有词库里真的写了
 * {@code [事件]EntityJoinLevelEvent} 这类词条时，才用
 * {@code IEventBus.addListener(Class, Consumer)} 动态注册对应的监听器。
 * 这样「能力全覆盖」和「成本按订阅付」可以同时成立：没有插件订阅高频 tick 事件时，
 * 一次 JNI 都不会多跨。</p>
 *
 * <p>payload 用 Java getter 名派生字段名（{@code getEntity()} → {@code %entity%}），
 * 复杂对象转成结构化 JSON；同时补一套中文别名（{@code %玩家名%} / {@code %物品%} /
 * {@code %面%} …），英文键一律保留，旧插件不受影响。清单里的 {@code cnNames} 给出
 * 中文事件名（{@code [事件]右键物品} → {@code PlayerInteractEvent.RightClickItem}），
 * 由 {@code tools/gen_events.py} 随清单一起生成，升级 NeoForge 不会丢。
 * 内置中文事件（玩家聊天 / 实体受伤 …）仍由 {@link NebulaMod} 的手写 handler
 * 处理，不在这里重复分发。</p>
 */
public final class EventBridge {
    private static final Logger LOG = LogManager.getLogger("nebula");
    private static final String RESOURCE = "/nebula/events.json";

    private record CatalogEntry(String className, int throttle, String hint) {
    }

    private record Active(Consumer<Event> consumer, CatalogEntry entry) {
    }

    private static Map<String, CatalogEntry> catalog;
    /** 中文事件名 → 清单里的 Java 事件简名；英文订阅名不走这张表。 */
    private static Map<String, String> cnToEn = Map.of();
    /** 清单简名 → 中文事件名，用于往 payload 里写 {@code %事件名%}。 */
    private static Map<String, String> enToCn = Map.of();
    private static final Map<String, Active> ACTIVE = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> COUNTS = new HashMap<>();
    private static long handle = 0L;

    private EventBridge() {
    }

    /** 加载 jar 内的事件清单；只做一次，失败时退化为空表而不是拖垮开服。 */
    private static void init() {
        if (catalog != null) {
            return;
        }
        Map<String, CatalogEntry> map = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        String version = "?";
        try (InputStream in = EventBridge.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warn("[星云] jar 内缺少 {}，通用事件桥不可用", RESOURCE);
                catalog = map;
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            version = root.has("neoforge") ? root.get("neoforge").getAsString() : "?";
            for (JsonElement el : root.getAsJsonArray("events")) {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                map.put(name, new CatalogEntry(
                        o.get("class").getAsString(),
                        o.has("throttle") ? o.get("throttle").getAsInt() : 0,
                        o.has("hint") ? o.get("hint").getAsString() : ""));
            }
            if (root.has("cnNames")) {
                for (Map.Entry<String, JsonElement> el : root.getAsJsonObject("cnNames").entrySet()) {
                    String en = el.getValue().getAsString();
                    aliases.put(el.getKey(), en);
                    reverse.put(en, el.getKey());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[星云] 事件清单读取失败，通用事件桥不可用", t);
        }
        catalog = map;
        cnToEn = aliases;
        enToCn = reverse;
        LOG.info("[星云] 通用事件桥清单：NeoForge {}，{} 个事件类，{} 个中文名，按订阅注册",
                version, map.size(), aliases.size());
    }

    /**
     * 按词库当前的 {@code [事件]} 词条刷新监听器。开服 loadAll 之后、以及
     * {@code /nebula load|unload|debug|undebug} 之后各调一次。
     */
    static void refresh(long h) {
        handle = h;
        init();
        Set<String> wanted = new HashSet<>();
        try {
            JsonElement root = JsonParser.parseString(NativeBridge.eventTriggers(h));
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    wanted.add(el.getAsString());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[星云] 读取事件订阅表失败，保持现有监听器不变", t);
            return;
        }

        for (Iterator<Map.Entry<String, Active>> it = ACTIVE.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Active> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                try {
                    NeoForge.EVENT_BUS.unregister(entry.getValue().consumer());
                } catch (Throwable ignored) {
                    // 总线可能已经关了，忽略即可。
                }
                COUNTS.remove(entry.getKey());
                it.remove();
            }
        }

        int added = 0;
        int builtin = 0;
        for (String name : wanted) {
            if (ACTIVE.containsKey(name)) {
                continue;
            }
            // 中文事件名先经别名表换成清单里的 Java 简名；英文订阅名原样透传。
            String catalogName = cnToEn.getOrDefault(name, name);
            CatalogEntry meta = catalog.get(catalogName);
            if (meta == null) {
                // 6 个内置中文事件 / 指令事件由宿主自己的 handler 分发，这里不能重复注册。
                builtin++;
                continue;
            }
            if (!meta.hint().isEmpty()) {
                LOG.warn("[星云] 事件 {} 属于加载期事件，专用服运行时通常收不到", name);
            }
            if (register(name, catalogName, meta)) {
                added++;
                if (!name.equals(catalogName)) {
                    // 中文别名有没有真的解析到事件类，日志里要能一眼看到。
                    LOG.info("[星云] 中文事件 {} → {} 已注册", name, catalogName);
                }
            }
        }
        LOG.info("[星云] 通用事件桥：订阅 {} 个（内置 {} 个），新注册 {} 个，累计 {} 个",
                wanted.size(), builtin, added, ACTIVE.size());
    }

    /** 停服时摘掉全部动态监听器，避免总线持有已销毁引擎的句柄。 */
    static void clear() {
        for (Active active : ACTIVE.values()) {
            try {
                NeoForge.EVENT_BUS.unregister(active.consumer());
            } catch (Throwable ignored) {
                // 停服阶段总线可能已经关闭。
            }
        }
        ACTIVE.clear();
        COUNTS.clear();
        handle = 0L;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean register(String name, String catalogName, CatalogEntry meta) {
        Class type;
        try {
            type = Class.forName(meta.className());
        } catch (Throwable t) {
            LOG.warn("[星云] 事件 {} 的类 {} 不可用（客户端专用？）", name, meta.className());
            return false;
        }
        if (!Event.class.isAssignableFrom(type)
                || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())) {
            // 清单里也有辅助枚举 / 接口 / 抽象基类，运行时统一过滤掉。
            return false;
        }
        Consumer<Event> consumer = ev -> onEvent(name, catalogName, meta, ev);
        try {
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, type, consumer);
            ACTIVE.put(name, new Active(consumer, meta));
            return true;
        } catch (Throwable t) {
            LOG.warn("[星云] 事件 {} 注册失败：{}", name, t.toString());
            return false;
        }
    }

    private static void onEvent(String name, String catalogName, CatalogEntry meta, Event event) {
        if (meta.throttle() > 0) {
            int n = COUNTS.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            if (n % meta.throttle() != 0) {
                return;
            }
        }
        if (handle == 0L) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            String payload = payloadOf(event, name, catalogName);
            JsonObject result = NebulaMod.parse(NativeBridge.dispatch(handle, name, payload));
            if (event instanceof ICancellableEvent cancelable
                    && result.has("cancel")
                    && result.get("cancel").getAsBoolean()) {
                cancelable.setCanceled(true);
            }
            NebulaMod.broadcastOutputs(server, result);
            NebulaMod.executeActions(result, server);
            // 调试自动重编译（`refresh_debug_plugin`）也会跑 `[f]初始化` 并产生注册动作。
            // 普通事件里 `批次` 是上一轮编译的值、BATCH_PLUGINS 为空，commit 是空操作。
            if (result.has("批次")) {
                nebula.neoforge.content.MobRegistry.commit(result.get("批次").getAsLong());
                nebula.neoforge.content.MobSync.markDirty();
                nebula.neoforge.content.MobSync.sync(server);
            }
        } catch (Throwable t) {
            LOG.warn("[星云] 通用事件 {} 分发失败", name, t);
        }
    }

    /** 反射提取 public getter，按 Java 字段名注入；再补一套中文别名，不持有任何 Minecraft 对象。 */
    private static String payloadOf(Event event, String trigger, String catalogName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("eventClass", event.getClass().getName());
        for (Method m : event.getClass().getMethods()) {
            if (m.getParameterCount() != 0
                    || Modifier.isStatic(m.getModifiers())
                    || m.getDeclaringClass() == Object.class
                    || m.getReturnType() == void.class) {
                continue;
            }
            String n = m.getName();
            boolean isGetter = n.startsWith("get") && n.length() > 3;
            boolean isBoolean = n.startsWith("is") && n.length() > 2;
            if ((!isGetter && !isBoolean) || n.equals("getClass")) {
                continue;
            }
            String key = keyOf(n, isGetter ? 3 : 2);
            try {
                putValue(payload, key, m.invoke(event));
            } catch (Throwable ignored) {
                // 某些 getter 只在客户端可用，或会访问尚未初始化的状态，跳过该字段。
            }
        }
        addChineseFields(payload, event, trigger, catalogName);
        return payload.toString();
    }

    /**
     * 中文 payload 别名：英文键原样保留，中文键是「另加一份」。
     * 只按已有的 JSON 字段做映射，不依赖任何具体 MC 类，事件类换版本也不会编译失败。
     */
    private static void addChineseFields(JsonObject p, Event event, String trigger, String catalogName) {
        String cls = event.getClass().getName();
        // 玩家类事件（交互 + 登录/退出等 PlayerEvent 内部类）统一给「玩家 / 玩家名」，
        // 其它事件的 entity 可能是怪物，只能叫「实体 / 实体名」。
        boolean playerEvent = cls.contains("PlayerInteractEvent") || cls.contains("PlayerEvent");
        p.addProperty("事件类", cls);
        p.addProperty("原事件名", catalogName);
        String alias = enToCn.get(catalogName);
        p.addProperty("事件名", alias != null ? alias : trigger);

        JsonObject entity = object(p, "entity");
        if (entity != null) {
            p.add(playerEvent ? "玩家" : "实体", entitySnapshot(entity));
            p.addProperty(playerEvent ? "玩家名" : "实体名", text(entity, "name"));
            p.addProperty("维度", text(entity, "dimension"));
            p.addProperty("类型", text(entity, "type"));
            if (playerEvent) {
                p.addProperty("x", text(entity, "x"));
                p.addProperty("y", text(entity, "y"));
                p.addProperty("z", text(entity, "z"));
            }
        }

        JsonObject target = object(p, "target");
        if (target != null) {
            p.add("目标", entitySnapshot(target));
            p.addProperty("目标名", text(target, "name"));
        }

        JsonObject stack = object(p, "itemStack");
        if (stack == null) {
            stack = object(p, "item");
        }
        if (stack != null) {
            p.addProperty("物品", text(stack, "item"));
            p.addProperty("数量", text(stack, "count"));
        }

        String hand = text(p, "hand");
        if (!hand.isEmpty()) {
            boolean main = "MAIN_HAND".equals(hand);
            p.addProperty("手", main ? "主手" : "OFF_HAND".equals(hand) ? "副手" : hand);
            p.addProperty("主手", main ? "是" : "否");
        }

        JsonObject pos = object(p, "pos");
        if (pos != null) {
            p.add("坐标", copyObject(pos));
            p.addProperty("方块x", text(pos, "x"));
            p.addProperty("方块y", text(pos, "y"));
            p.addProperty("方块z", text(pos, "z"));
        }

        String face = text(p, "face");
        if (!face.isEmpty()) {
            p.addProperty("面", switch (face) {
                case "UP" -> "上";
                case "DOWN" -> "下";
                case "NORTH" -> "北";
                case "SOUTH" -> "南";
                case "EAST" -> "东";
                case "WEST" -> "西";
                default -> face;
            });
            p.addProperty("面英文", face);
        }

        addResult(p, "useItem", "用物品");
        addResult(p, "useBlock", "用方块");
        String action = text(p, "action");
        if (!action.isEmpty()) {
            p.addProperty("动作", switch (action) {
                case "START" -> "开始";
                case "ABORT" -> "中止";
                case "STOP" -> "停止";
                case "CLIENT_HOLD" -> "持续";
                default -> action;
            });
        }
    }

    private static void addResult(JsonObject p, String en, String cn) {
        String v = text(p, en);
        if (v.isEmpty()) {
            return;
        }
        p.addProperty(cn, switch (v) {
            case "ALLOW" -> "允许";
            case "DENY" -> "拒绝";
            case "PASS" -> "跳过";
            default -> "默认";
        });
    }

    /** 把 entityJson 的英文字段翻成中文快照；坐标键沿用 x/y/z（命令里要拼数字）。 */
    private static JsonObject entitySnapshot(JsonObject e) {
        JsonObject j = new JsonObject();
        j.addProperty("名字", text(e, "name"));
        j.addProperty("类型", text(e, "type"));
        j.addProperty("维度", text(e, "dimension"));
        j.addProperty("编号", text(e, "id"));
        j.addProperty("x", text(e, "x"));
        j.addProperty("y", text(e, "y"));
        j.addProperty("z", text(e, "z"));
        return j;
    }

    private static JsonObject object(JsonObject p, String key) {
        JsonElement e = p.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject copyObject(JsonObject o) {
        JsonObject j = new JsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            j.add(e.getKey(), e.getValue().deepCopy());
        }
        return j;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static String keyOf(String method, int prefix) {
        String rest = method.substring(prefix);
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static void putValue(JsonObject p, String key, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof CharSequence s) {
            p.addProperty(key, s.toString());
        } else if (v instanceof Number n) {
            p.addProperty(key, n);
        } else if (v instanceof Boolean b) {
            p.addProperty(key, b);
        } else if (v instanceof Character c) {
            p.addProperty(key, c.toString());
        } else if (v instanceof Enum<?> e) {
            p.addProperty(key, e.name());
        } else if (v instanceof UUID u) {
            p.addProperty(key, u.toString());
        } else if (v instanceof Identifier rl) {
            p.addProperty(key, rl.toString());
        } else if (v instanceof BlockPos pos) {
            JsonObject j = new JsonObject();
            j.addProperty("x", pos.getX());
            j.addProperty("y", pos.getY());
            j.addProperty("z", pos.getZ());
            p.add(key, j);
        } else if (v instanceof Component c) {
            p.addProperty(key, c.getString());
        } else if (v instanceof Entity e) {
            p.add(key, entityJson(e));
        } else if (v instanceof ItemStack stack) {
            JsonObject j = new JsonObject();
            j.addProperty("item", stack.isEmpty()
                    ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            j.addProperty("count", stack.getCount());
            p.add(key, j);
        } else if (v instanceof Collection<?> c) {
            JsonArray a = new JsonArray();
            int i = 0;
            for (Object item : c) {
                if (i++ >= 32) {
                    break;
                }
                a.add(shortString(item));
            }
            p.add(key, a);
        } else if (v.getClass().isArray()) {
            JsonArray a = new JsonArray();
            Object[] arr = (Object[]) v;
            for (int i = 0; i < arr.length && i < 32; i++) {
                a.add(shortString(arr[i]));
            }
            p.add(key, a);
        } else {
            p.addProperty(key, shortString(v));
        }
    }

    private static JsonObject entityJson(Entity e) {
        JsonObject j = new JsonObject();
        j.addProperty("name", e.getName().getString());
        j.addProperty("id", e.getId());
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        j.addProperty("type", type == null ? "" : type.toString());
        j.addProperty("dimension", e.level().dimension().identifier().toString());
        j.addProperty("x", e.getX());
        j.addProperty("y", e.getY());
        j.addProperty("z", e.getZ());
        return j;
    }

    /** 未知类型只留短字符串，避免把整个 Level / Registry 的 toString 塞进 payload。 */
    private static String shortString(Object v) {
        if (v == null) {
            return "";
        }
        try {
            String s = String.valueOf(v);
            return s.length() <= 200 ? s : v.getClass().getName();
        } catch (Throwable t) {
            return v.getClass().getName();
        }
    }
}
