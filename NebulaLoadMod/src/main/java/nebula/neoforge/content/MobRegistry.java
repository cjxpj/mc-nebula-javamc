package nebula.neoforge.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 运行期生物品种表：词库声明什么，这里就有什么。
 *
 * <p>三条不变量：</p>
 * <ol>
 *   <li><b>品种 → 槽位</b>：槽位来自 {@link NebulaEntities} 的编译期池，按体型档分配。</li>
 *   <li><b>按插件整体替换</b>：每次 `/nebula load|unload` 的动作视为该插件的一批完整声明，
 *       本批没出现的品种自动下线（{@link #commit(long)}）。这样删掉一行 `$世界.注册生物$` 不需要写注销。</li>
 *   <li><b>归属优先</b>：所有条目记 {@code plugin}，卸载时按插件强制回收，不依赖词库写 `[f]卸载`。</li>
 * </ol>
 *
 * <p>本类只做数据与派生，不碰 Minecraft 对象状态；生成 / 属性应用在 {@link DynamicMob}。</p>
 */
public final class MobRegistry {
    private static final Logger LOG = LogManager.getLogger("nebula");

    private static final Map<String, MobDef> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> EGG_MARKERS = new HashMap<>();
    private static final Map<Integer, String> SLOT_OWNER = new HashMap<>();
    private static final Set<String> BATCH_PLUGINS = new LinkedHashSet<>();
    /** `$怪.列表$` 的一个品种最多返回多少条（与词库 `列表 [上限]` 的钳制一致）。 */
    private static final int SNAPSHOT_LIMIT = 256;
    /** 最近一次快照对应的服务端 tick；同一 tick 内重复查询直接复用。 */
    private static long snapshotTick = Long.MIN_VALUE;
    private static final Map<String, JsonArray> SNAPSHOT = new HashMap<>();
    private static volatile boolean ready;

    private MobRegistry() {
    }

    /** 一个品种的完整定义（字段名与词库对象一一对应）。 */
    public record MobDef(
            String id,
            String plugin,
            String name,
            String size,
            boolean persistent,
            double health,
            double speed,
            double jump,
            double knockback,
            int slots,
            double containerDistance,
            String collision,
            boolean dropOnDeath,
            JsonObject look,
            JsonObject anim,
            JsonArray items,
            JsonArray goals,
            JsonObject interact,
            JsonArray drops,
            int slot,
            long batch) {

        /**
         * 开盖速度：每 tick 的进度步进（0.18 ≈ 5.5 tick 全开）。
         *
         * <p>这是唯一留在服务端的动画量 —— 开合进度由服务端算、同步给客户端，
         * 所以要在这里读，而不是塞进客户端声明。词库写 {@code $怪.动画 开合 x$} 覆盖。</p>
         */
        public float openStep() {
            if (anim == null || !anim.has("开合") || !anim.get("开合").isJsonPrimitive()) {
                return 0.18F;
            }
            try {
                return (float) Math.max(0.02, Math.min(1.0, anim.get("开合").getAsDouble()));
            } catch (RuntimeException e) {
                return 0.18F;
            }
        }

        /**
         * 交互距离（格）：{@code $怪.设 容器距离 n$}，玩家离多远还能开着箱子。
         *
         * <p>夹到 1..64 —— 0 或负数会让容器永远 {@code stillValid=false}（界面一开就关），
         * 太大等于「在出生点开箱」，都不是作者想要的。</p>
         */
        public double containerRange() {
            double value = containerDistance > 0 ? containerDistance : 8.0;
            return Math.max(1.0, Math.min(64.0, value));
        }

        /** 右键行为：{@code 开容器}（缺省）/ {@code 无}（交还给 `[事件]右键实体`）/ {@code 给予}。 */
        public String interactKind() {
            return interact == null ? "开容器" : str(interact, "类型", "开容器");
        }

        /** {@code 交互 给予} 的物品 id；没声明返回空串。 */
        public String interactItem() {
            return interact == null ? "" : str(interact, "物品", "");
        }

        /** {@code 交互 给予} 的数量（钳到 1..64）。 */
        public int interactCount() {
            if (interact == null || !interact.has("数量")) {
                return 1;
            }
            try {
                return Math.max(1, Math.min(64, interact.get("数量").getAsInt()));
            } catch (RuntimeException e) {
                return 1;
            }
        }

        /**
         * 碰撞箱（宽, 高，格）；{@code null} 表示「用体型档的注册期尺寸」。
         *
         * <p>优先级：手写 {@code $怪.设 碰撞箱 0.9,1.0$} &gt; {@code $怪.设 碰撞箱 自动$}
         * （按 {@code $怪.造型$} 的体 / 盖并集算）&gt; 不覆盖。没有造型时「自动」等于不覆盖。</p>
         */
        public double[] collisionSize() {
            double[] manual = manualCollision();
            if (manual != null) {
                return manual;
            }
            String mode = collision == null ? "" : collision.trim();
            if (!"自动".equals(mode) && !"auto".equalsIgnoreCase(mode)) {
                return null;
            }
            return shapeCollision();
        }

        /** 手动声明的宽/高；不是「宽,高」形式时返回 {@code null}。 */        private double[] manualCollision() {
            String mode = collision == null ? "" : collision.trim();
            if (mode.isEmpty() || "自动".equals(mode) || "auto".equalsIgnoreCase(mode)) {
                return null;
            }
            String[] parts = mode.split("[,，xX*\\s]+");
            if (parts.length != 2) {
                return null;
            }
            try {
                double width = Double.parseDouble(parts[0]);
                double height = Double.parseDouble(parts[1]);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                return new double[]{
                        Math.max(0.05, Math.min(8.0, width)),
                        Math.max(0.05, Math.min(8.0, height))};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * 造型并集 → 碰撞箱：宽取 x/z 较大者，高取盒子最高点。
         *
         * <p>盒子坐标是 1/16 格、原点在方块角落，而实体位置在方块中心，所以这里只用「跨度」，
         * 不用绝对位置 —— 偏心的造型不会让碰撞箱跟着整体平移。</p>
         */
        private double[] shapeCollision() {
            if (look == null || (!look.has("体") && !look.has("盖"))) {
                return null;
            }
            double[] body = box(look, "体", 1, 0, 1, 15, 14, 15);
            double[] lid = box(look, "盖", 1, 14, 1, 15, 16, 15);
            double spanX = Math.max(body[3], lid[3]) - Math.min(body[0], lid[0]);
            double spanZ = Math.max(body[5], lid[5]) - Math.min(body[2], lid[2]);
            double top = Math.max(body[4], lid[4]);
            double width = Math.max(spanX, spanZ) / 16.0;
            double height = top / 16.0;
            return new double[]{
                    Math.max(0.05, Math.min(8.0, width)),
                    Math.max(0.05, Math.min(8.0, height))};
        }
    }

    public static boolean ready() {
        return ready;
    }

    public static void markReady() {
        ready = true;
    }

    public static synchronized MobDef byId(String id) {
        return BY_ID.get(id);
    }

    public static synchronized EntityType<?> entityType(String id) {
        MobDef def = BY_ID.get(id);
        return def == null ? null : NebulaEntities.SLOTS[def.slot()].get();
    }

    /** 动作落地：新增或更新一个品种。 */
    public static synchronized void upsert(JsonObject action) {
        String id = str(action, "品种", "");
        if (id.isBlank()) {
            return;
        }
        String plugin = str(action, "插件", "");
        JsonObject def = action.has("定义") && action.get("定义").isJsonObject()
                ? action.getAsJsonObject("定义") : new JsonObject();
        long batch = action.has("批次") ? action.get("批次").getAsLong() : 0L;
        String size = str(def, "体型", "normal");
        int slot = allocate(id, size);
        if (slot < 0) {
            LOG.warn("[星云] 注册生物 {}：{} 档槽位已满（每档 {} 个），跳过。",
                    id, size, NebulaEntities.PER_ARCHETYPE);
            return;
        }
        JsonObject look = def.has("外观") && def.get("外观").isJsonObject()
                ? def.getAsJsonObject("外观") : new JsonObject();
        JsonObject anim = def.has("动画") && def.get("动画").isJsonObject()
                ? def.getAsJsonObject("动画") : new JsonObject();
        JsonArray items = def.has("物品") && def.get("物品").isJsonArray()
                ? def.getAsJsonArray("物品") : new JsonArray();
        JsonArray goals = def.has("目标") && def.get("目标").isJsonArray()
                ? def.getAsJsonArray("目标") : new JsonArray();
        JsonObject interact = def.has("交互") && def.get("交互").isJsonObject()
                ? def.getAsJsonObject("交互") : null;
        JsonArray drops = def.has("掉落") && def.get("掉落").isJsonArray()
                ? def.getAsJsonArray("掉落") : new JsonArray();
        // 「方块模型」参数由宿主按槽位决定（外观方块是编译期全量注册的，名字只能按槽位派生）。
        // 词库写了别的名字不会生效 —— 但要出声，否则作者会以为换模型有效果。
        String declaredModel = str(look, "方块模型", "");
        String expectedModel = "nebula_bridge:mob_body_" + slot;
        if (!declaredModel.isBlank() && !declaredModel.equals(expectedModel)) {
            LOG.warn("[星云] 注册生物 {}：外观「方块模型」{} 被槽位覆盖，实际使用 {}（体型 {}）。",
                    id, declaredModel, expectedModel, size);
        }
        BY_ID.put(id, new MobDef(
                id,
                plugin,
                str(def, "名称", id),
                size,
                bool(def, "持久", true),
                num(def, "血量", 20),
                num(def, "速度", 0.22),
                num(def, "跳跃", 0.42),
                num(def, "击退", 0.4),
                (int) num(def, "容器格数", 0),
                num(def, "容器距离", 8),
                str(def, "碰撞箱", ""),
                bool(def, "死亡掉落", true),
                look,
                anim,
                items,
                goals,
                interact,
                drops,
                slot,
                batch));
        BATCH_PLUGINS.add(plugin);
        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if ("刷怪蛋".equals(str(item, "用途", ""))) {
                String marker = str(item, "标记", "");
                if (!marker.isBlank()) {
                    EGG_MARKERS.put(marker, id);
                }
            }
        }
    }

    /** 单品种即时下线（`$怪.卸载$`）。 */
    public static synchronized void remove(String id) {
        MobDef old = BY_ID.remove(id);
        if (old != null) {
            SLOT_OWNER.remove(old.slot(), id);
        }
        EGG_MARKERS.entrySet().removeIf(entry -> entry.getValue().equals(id));
    }

    /**
     * 按批次收口：本批出现过声明的插件，其名下未在本批出现的品种全部下线。
     *
     * <p>编译失败的插件不会进入 {@code BATCH_PLUGINS}（Rust 侧已经丢掉了它这一轮的动作），
     * 所以旧对象集保持不动。</p>
     */
    public static synchronized void commit(long batch) {
        if (BATCH_PLUGINS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, MobDef>> iterator = BY_ID.entrySet().iterator();
        while (iterator.hasNext()) {
            MobDef def = iterator.next().getValue();
            if (BATCH_PLUGINS.contains(def.plugin()) && def.batch() != batch) {
                SLOT_OWNER.remove(def.slot(), def.id());
                iterator.remove();
                LOG.info("[星云] 生物下线：{}（插件 {} 本批未声明）", def.id(), def.plugin());
            }
        }
        EGG_MARKERS.entrySet().removeIf(entry -> !BY_ID.containsKey(entry.getValue()));
        BATCH_PLUGINS.clear();
    }

    /** 按标记或品种 id 找品种（`刷怪蛋生成` 的 `类型` 参数两种都收）。 */
    public static synchronized String speciesOf(String typeOrMarker) {
        if (BY_ID.containsKey(typeOrMarker)) {
            return typeOrMarker;
        }
        return EGG_MARKERS.get(typeOrMarker);
    }

    /**
     * 按标记找一条「生物附带的物品定义」（当前就是刷怪蛋）。
     *
     * <p>蛋归生物插件声明（`$怪.加物品 刷怪蛋 …$`），物品插件不该再复制一份，
     * 所以 `给物品 chest_mob_egg` 走这里兜底。</p>
     */
    public static synchronized JsonObject itemByMarker(String marker) {
        for (MobDef def : BY_ID.values()) {
            for (JsonElement element : def.items()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (marker.equals(str(item, "标记", ""))) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * 该品种已生成的实体（JSON 数组），供 {@code $怪.列表$}。
     *
     * <p><b>按 tick 做一次快照</b>：词库典型写法是 `[事件]每秒` 里先 `$怪.列表 64$` 再逐只巡逻，
     * 每只怪都会调一次这里。若每次都遍历「所有维度 × 全部实体」，N 只怪就是 O(N × 总实体数)，
     * 怪一多就明显卡。现在同一 tick 内只扫一遍，结果按品种分组复用。</p>
     */
    public static synchronized String entitiesJson(MinecraftServer server, String id, int limit) {
        if (server == null || byId(id) == null) {
            return "[]";
        }
        snapshot(server);
        JsonArray all = SNAPSHOT.get(id);
        if (all == null || all.isEmpty()) {
            return "[]";
        }
        int max = Math.max(1, Math.min(limit, 256));
        if (all.size() <= max) {
            return all.toString();
        }
        JsonArray cut = new JsonArray();
        for (int i = 0; i < max; i++) {
            cut.add(all.get(i));
        }
        return cut.toString();
    }

    /** 本 tick 的「品种 → 实体列表」快照；只在 tick 变化时重建一次。 */
    private static synchronized void snapshot(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick == snapshotTick) {
            return;
        }
        snapshotTick = tick;
        SNAPSHOT.clear();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof DynamicMob mob)) {
                    continue;
                }
                String species = mob.species();
                if (species.isEmpty()) {
                    continue;
                }
                JsonArray bucket = SNAPSHOT.get(species);
                if (bucket == null) {
                    bucket = new JsonArray();
                    SNAPSHOT.put(species, bucket);
                }
                if (bucket.size() >= SNAPSHOT_LIMIT) {
                    continue;
                }
                JsonObject one = new JsonObject();
                one.addProperty("uuid", entity.getUUID().toString());
                one.addProperty("品种", species);
                one.addProperty("维度", level.dimension().identifier().toString());
                one.addProperty("x", entity.getX());
                one.addProperty("y", entity.getY());
                one.addProperty("z", entity.getZ());
                bucket.add(one);
            }
        }
    }

    /** 服务端创造页条目：由品种的 `物品[]` 派生，不再只认磁盘 `创造物品.json`。 */
    public static synchronized List<JsonObject> creativeEntries() {
        List<JsonObject> out = new ArrayList<>();
        for (MobDef def : BY_ID.values()) {
            for (JsonElement element : def.items()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String tab = str(item, "创造页", "");
                if (tab.isBlank()) {
                    continue;
                }
                String marker = str(item, "标记", "");
                JsonObject entry = new JsonObject();
                entry.addProperty("页", tab);
                entry.addProperty("基底", str(item, "基底", "minecraft:clay_ball"));
                entry.addProperty("模型", str(item, "模型", "nebula:item/" + marker));
                entry.addProperty("名称", str(item, "名称", marker));
                entry.addProperty("标记", marker);
                if (item.has("堆叠")) {
                    entry.add("堆叠", item.get("堆叠"));
                }
                if (item.has("稀有度")) {
                    entry.addProperty("稀有度", str(item, "稀有度", "common"));
                }
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * 资源包派生条目：外观方块、物品模型、客户端声明、语言、创造页。
     *
     * <p>只派生 JSON，<b>不造 PNG</b> —— 贴图仍由插件 {@code 资源/} 提供，缺文件时
     * 客户端是紫黑块，这是作者侧的问题，宿主不猜。</p>
     */
    public static synchronized Map<String, byte[]> entries() {
        Map<String, byte[]> out = new TreeMap<>();
        JsonObject lang = new JsonObject();
        Set<Integer> used = new LinkedHashSet<>();

        for (MobDef def : BY_ID.values()) {
            used.add(def.slot());
            String blockName = "mob_body_" + def.slot();
            String lidName = "mob_lid_" + def.slot();
            out.put("assets/nebula_bridge/blockstates/" + blockName + ".json",
                    utf8(blockState(blockName)));
            out.put("assets/nebula_bridge/models/block/" + blockName + ".json",
                    utf8(blockModel(def.look())));
            out.put("assets/nebula_bridge/blockstates/" + lidName + ".json",
                    utf8(blockState(lidName)));
            out.put("assets/nebula_bridge/models/block/" + lidName + ".json",
                    utf8(lidModel(def.look())));

            // 客户端声明一品种一份，文件名只放槽位号。
            // 资源包里的路径必须是纯 ASCII（Identifier 白名单 [a-z0-9_.-]），
            // 用插件名会拼出 `mob_箱子生物.json` 这类路径，被客户端整条丢弃：
            //   "Invalid path in datapack: nebula:client/mob_箱子生物.json, ignoring"
            // → ClientDecls 读不到 → DynamicMobRenderer 没有外观 → 什么都不画。
            JsonObject look = new JsonObject();
            look.addProperty("方块", "nebula_bridge:" + blockName);
            // 盖子单独一块方块：渲染器要在铰链上给它摆 pose，同一份提交里做不到。
            look.addProperty("盖子", "nebula_bridge:" + lidName);
            // 铰链 = 盖子盒子的「后上边缘」，客户端绕它转。y 取盖子顶面（缺省 14/16 = 老值），
            // z 固定方块后边界 —— 和 0.6.x 写死的 (14/16, 1.0) 像素级一致，改盖子高度才会变。
            if (def.look().has("盖")) {
                double[] lid = box(def.look(), "盖", 1, 14, 1, 15, 16, 15);
                look.add("铰链", array(lid[4] / 16.0, 1.0));
            }
            look.add("偏移", def.look().has("偏移")
                    ? def.look().get("偏移") : array(0, 0, 0));
            look.addProperty("阴影", def.look().has("阴影")
                    ? def.look().get("阴影").getAsDouble() : 0.45);
            // 动画参数里只有「开合」留在服务端算进度，其余全是客户端渲染量。
            if (def.anim() != null) {
                for (String key : new String[]{"弹跳", "前倾", "开盖", "摇摆", "缩放", "旋转", "浮空"}) {
                    if (def.anim().has(key)) {
                        look.add(key, def.anim().get(key));
                    }
                }
            }
            // 客户端拿不到品种 id（那是服务端 NBT），只能按槽位查外观，
            // 所以这里的 key 用槽位实体 id，而不是品种 id。
            JsonObject looks = new JsonObject();
            looks.add("nebula_bridge:mob_" + def.slot(), look);
            JsonObject decl = new JsonObject();
            decl.add("实体外观", looks);
            out.put("assets/nebula/client/mob_" + def.slot() + ".json", utf8(decl));

            lang.addProperty("entity.nebula_bridge.mob_" + def.slot(), def.name());
            lang.addProperty("entity.nebula_bridge." + slug(def.id()), def.name());

            JsonArray creative = new JsonArray();
            for (JsonElement element : def.items()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String marker = str(item, "标记", "");
                if (marker.isBlank()) {
                    continue;
                }
                String model = str(item, "模型", "nebula:item/" + marker);
                out.put("assets/nebula/items/item/" + marker + ".json", utf8(itemList(model)));
                out.put("assets/nebula/models/item/" + marker + ".json",
                        utf8(itemModel(model, str(item, "贴图", ""))));
                if (!str(item, "创造页", "").isBlank()) {
                    // 整份定义直接当创造页条目：CreativeItems 与 给物品 用同一个
                    // ItemRegistry.stack 构造，颜色 / 描述 / 属性都不会在创造页里丢。
                    JsonObject entry = item.deepCopy();
                    entry.addProperty("页", str(item, "创造页", "杂项"));
                    if (!model.isBlank()) {
                        entry.addProperty("模型", model);
                    }
                    // 刷怪蛋走真 SpawnEggItem：基底换成通用蛋，实体类型由 entity_data 决定。
                    // 词库只声明「用途=刷怪蛋」，不需要知道 mod 那个物品 id。
                    if ("刷怪蛋".equals(str(item, "用途", ""))) {
                        entry.addProperty("基底", "nebula_bridge:spawn_egg");
                        entry.addProperty("实体", "nebula_bridge:mob_" + def.slot());
                    }
                    creative.add(entry);
                }
            }
            if (!creative.isEmpty()) {
                out.put("assets/nebula/creative/mob_" + def.slot() + ".json", utf8(creative));
            }
        }
        // 空槽位也要有 blockstate + 空模型：体 / 盖两套方块都是编译期全量注册的，
        // 缺模型时客户端每次重载都刷一条 "Missing model for variant"（64 条淹掉真问题）。
        for (int slot = 0; slot < NebulaEntities.SLOT_COUNT; slot++) {
            if (used.contains(slot)) {
                continue;
            }
            for (String prefix : new String[]{"mob_body_", "mob_lid_"}) {
                String blockName = prefix + slot;
                out.put("assets/nebula_bridge/blockstates/" + blockName + ".json",
                        utf8(blockState(blockName)));
                out.put("assets/nebula_bridge/models/block/" + blockName + ".json",
                        utf8(emptyModel()));
            }
        }
        if (!lang.isEmpty()) {
            out.put("assets/nebula_bridge/lang/zh_cn.json", utf8(lang));
        }
        return out;
    }

    // ────────────────────────── 内部 ──────────────────────────

    private static int allocate(String id, String size) {
        int archetype = archetypeOf(size);
        MobDef old = BY_ID.get(id);
        if (old != null && NebulaEntities.SLOT_ARCHETYPE[old.slot()] == archetype) {
            return old.slot();
        }
        if (old != null) {
            SLOT_OWNER.remove(old.slot(), id);
        }
        int base = archetype * NebulaEntities.PER_ARCHETYPE;
        for (int i = base; i < base + NebulaEntities.PER_ARCHETYPE; i++) {
            String owner = SLOT_OWNER.get(i);
            if (owner == null || owner.equals(id)) {
                SLOT_OWNER.put(i, id);
                return i;
            }
        }
        return -1;
    }

    private static int archetypeOf(String size) {
        for (int i = 0; i < NebulaEntities.ARCHETYPES.length; i++) {
            if (NebulaEntities.ARCHETYPES[i].equals(size)) {
                return i;
            }
        }
        return 2; // normal
    }

    private static JsonObject blockState(String blockName) {
        JsonObject model = new JsonObject();
        JsonObject one = new JsonObject();
        one.addProperty("model", "nebula_bridge:block/" + blockName);
        model.add("", one);
        JsonObject out = new JsonObject();
        out.add("variants", model);
        return out;
    }

    /**
     * 体 / 盖共用的贴图引用（贴图来自插件 {@code 资源/方块材质/}）。
     *
     * <p>主贴图是 {@code $怪.外观$} 第 3 参，槽位名固定 {@code crate}；
     * {@code $怪.材质 名字 贴图$} 注册的额外贴图按字母序编成 {@code t0}、{@code t1}…，
     * 供 {@code $怪.贴图 … 名字$} 按面引用。槽位名必须 ASCII —— 它会被当作 {@code #槽位} 引用。</p>
     */
    private static JsonObject lookTextures(JsonObject look) {
        String texture = str(look, "贴图", "nebula_bridge:block/mob_body");
        JsonObject textures = new JsonObject();
        textures.addProperty("particle", texture);
        textures.addProperty("crate", texture);
        JsonObject mats = materials(look);
        if (mats != null) {
            for (Map.Entry<String, String> entry : textureSlots(mats).entrySet()) {
                textures.addProperty(entry.getValue(), str(mats, entry.getKey(), texture));
            }
        }
        return textures;
    }

    /** 声明里的额外贴图表（`$怪.材质$`）；没声明返回 {@code null}。 */
    private static JsonObject materials(JsonObject look) {
        return look != null && look.has("材质") && look.get("材质").isJsonObject()
                ? look.getAsJsonObject("材质") : null;
    }

    /** 材质名 → 模型槽位名（{@code t0}…），按字母序编号保证两处调用一致。 */
    private static Map<String, String> textureSlots(JsonObject mats) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mats == null) {
            return out;
        }
        List<String> names = new ArrayList<>(mats.keySet());
        java.util.Collections.sort(names);
        int index = 0;
        for (String name : names) {
            out.put(name, "t" + index++);
        }
        return out;
    }

    /** 材质名 → {@code #槽位}；没注册的名字退回主贴图（{@code #crate}），并留一条 WARN。 */
    private static String textureRef(JsonObject look, String material) {
        if (material == null || material.isBlank()) {
            return "#crate";
        }
        String slot = textureSlots(materials(look)).get(material);
        if (slot == null) {
            LOG.warn("[星云] 外观：面引用了未注册的材质「{}」，退回主贴图（先写 $怪.材质 {} 贴图$）",
                    material, material);
            return "#crate";
        }
        return "#" + slot;
    }

    /**
     * 箱体：缺省 0..14 高，四面贴整张图（箱子的「脸」），顶 / 底取贴图底部深色，
     * 开盖后从上往下看是「箱内」而不是又一张脸。
     *
     * <p>几何可由 {@code $怪.造型 体 …$} 覆盖（6 个 1/16 格坐标），UV 由
     * {@code $怪.贴图 体 <面> …$} 逐面覆盖；两者都没写时就是上面这套箱子。</p>
     */
    private static JsonObject blockModel(JsonObject look) {
        double[] box = box(look, "体", 1, 0, 1, 15, 14, 15);
        JsonObject out = new JsonObject();
        out.add("textures", lookTextures(look));
        JsonArray elements = new JsonArray();
        JsonObject cube = new JsonObject();
        cube.add("from", array(box[0], box[1], box[2]));
        cube.add("to", array(box[3], box[4], box[5]));
        JsonObject faces = new JsonObject();
        for (String face : new String[]{"north", "east", "south", "west"}) {
            faces.add(face, faceQuad(look, "体", face, 0, 0, 16, 16));
        }
        faces.add("up", faceQuad(look, "体", "up", 0, 12, 16, 16));
        faces.add("down", faceQuad(look, "体", "down", 0, 12, 16, 16));
        cube.add("faces", faces);
        elements.add(cube);
        out.add("elements", elements);
        return out;
    }

    /**
     * 箱盖：缺省 14..16 两格厚的板，和箱体拼起来正好占满一格（闭合时看不出接缝）。
     * 侧面与顶面取贴图最上面 3 行的浅木色横条 —— 整张图贴上去会是一张压扁的「脸」，很怪。
     */
    private static JsonObject lidModel(JsonObject look) {
        double[] box = box(look, "盖", 1, 14, 1, 15, 16, 15);
        JsonObject out = new JsonObject();
        out.add("textures", lookTextures(look));
        JsonArray elements = new JsonArray();
        JsonObject cube = new JsonObject();
        cube.add("from", array(box[0], box[1], box[2]));
        cube.add("to", array(box[3], box[4], box[5]));
        JsonObject faces = new JsonObject();
        for (String face : new String[]{"north", "east", "south", "west", "up"}) {
            faces.add(face, faceQuad(look, "盖", face, 0, 0, 16, 3));
        }
        faces.add("down", faceQuad(look, "盖", "down", 0, 12, 16, 16));
        cube.add("faces", faces);
        elements.add(cube);
        out.add("elements", elements);
        return out;
    }

    /** 盒子几何：声明里的 {@code 体} / {@code 盖}（6 个 1/16 格坐标），缺省用传入值。 */
    private static double[] box(JsonObject look, String part, double... fallback) {
        double[] out = fallback.clone();
        if (look == null || !look.has(part) || !look.get(part).isJsonArray()) {
            return out;
        }
        JsonArray array = look.getAsJsonArray(part);
        for (int i = 0; i < Math.min(6, array.size()); i++) {
            try {
                out[i] = array.get(i).getAsDouble();
            } catch (RuntimeException ignored) {
                // 单个分量坏了就用缺省
            }
        }
        return out;
    }

    /**
     * 单面 UV：{@code 外观.uv.<部件>.<面>} 覆盖，缺省用传入值。
     *
     * <p>UV 是 0..16 的贴图坐标（允许 -32..32 做平铺），与原版模型 JSON 的 {@code uv} 同一套。
     * 值可以是 {@code [u0,v0,u1,v1]}，也可以是 {@code {"uv":[…],"贴图":"材质名"}} ——
     * 后者让这一面用 {@code $怪.材质$} 注册的另一张贴图。</p>
     */
    private static JsonObject faceQuad(JsonObject look, String part, String face,
                                       double u0, double v0, double u1, double v1) {
        double[] uv = {u0, v0, u1, v1};
        String material = "";
        JsonObject all = look != null && look.has("uv") && look.get("uv").isJsonObject()
                ? look.getAsJsonObject("uv") : null;
        JsonObject partUv = all != null && all.has(part) && all.get(part).isJsonObject()
                ? all.getAsJsonObject(part) : null;
        JsonElement declared = partUv != null && partUv.has(face) ? partUv.get(face) : null;
        JsonArray array = null;
        if (declared != null && declared.isJsonArray()) {
            array = declared.getAsJsonArray();
        } else if (declared != null && declared.isJsonObject()) {
            JsonObject one = declared.getAsJsonObject();
            material = str(one, "贴图", "");
            if (one.has("uv") && one.get("uv").isJsonArray()) {
                array = one.getAsJsonArray("uv");
            }
        }
        if (array != null) {
            for (int i = 0; i < Math.min(4, array.size()); i++) {
                try {
                    uv[i] = array.get(i).getAsDouble();
                } catch (RuntimeException ignored) {
                    // 单个分量坏了就用缺省
                }
            }
        }
        JsonObject one = new JsonObject();
        one.add("uv", array(uv[0], uv[1], uv[2], uv[3]));
        one.addProperty("texture", textureRef(look, material));
        return one;
    }

    /**
     * 空槽位的隐形模型：合法 JSON、无 element，客户端不再报缺失，也画不出东西。
     *
     * <p>必须留一个 {@code particle} 引用 —— 原版校验模型时会检查「贴图槽位是否都有定义」，
     * 连空模型也会因为 {@code particle} 缺失而逐条刷
     * {@code Missing texture references in model …}（64 个槽位 = 64 条）。</p>
     */
    private static JsonObject emptyModel() {
        JsonObject textures = new JsonObject();
        textures.addProperty("particle", "minecraft:block/stone");
        JsonObject out = new JsonObject();
        out.add("textures", textures);
        out.add("elements", new JsonArray());
        return out;
    }

    private static JsonObject itemList(String model) {
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", model);
        JsonObject out = new JsonObject();
        out.add("model", inner);
        return out;
    }

    /**
     * 物品模型：{@code parent=item/generated} + 单层贴图。
     *
     * <p>贴图优先级：{@code $怪.加物品$} 的第 6 参 {@code 贴图} 优先，缺省时用 {@code 模型} 推导。
     * 这是「模型名」与「贴图名」脱钩的唯一入口 —— 模型决定 {@code item_model} 组件指向哪份
     * definition，贴图决定用哪个 PNG，两者可以不同（同一张图给多件物品用）。</p>
     */
    private static JsonObject itemModel(String model, String texture) {
        String raw = texture == null || texture.isBlank() ? model : texture;
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", itemTextureRef(raw));
        JsonObject out = new JsonObject();
        out.addProperty("parent", "minecraft:item/generated");
        out.add("textures", textures);
        return out;
    }

    /** 把 `xxx` / `item/xxx` / `nebula:xxx` / `nebula:item/xxx` 统一成 `命名空间:item/xxx`。 */
    private static String itemTextureRef(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "nebula:item/missing";
        }
        String namespace = "nebula";
        String path = value;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (!path.contains("/")) {
            path = "item/" + path;
        }
        return namespace + ":" + path;
    }

    private static JsonArray array(double... values) {
        JsonArray out = new JsonArray();
        for (double value : values) {
            out.add(value);
        }
        return out;
    }

    private static String slug(String raw) {
        return raw.replace(':', '_').replace('/', '_');
    }

    private static byte[] utf8(JsonElement element) {
        return element.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String str(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double num(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        String value = str(object, key, "");
        if (value.isBlank()) {
            return fallback;
        }
        return "是".equals(value) || "true".equalsIgnoreCase(value);
    }
}
