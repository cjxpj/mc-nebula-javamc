package nebula.neoforge.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 运行期物品表：词库在 {@code [f]初始化} 里 {@code $世界.注册物品$} 声明什么，这里就有什么。
 *
 * <p>和 {@link MobRegistry} 同一套模型：</p>
 * <ol>
 *   <li><b>按插件整体替换</b>：每次 load / unload 的动作是一批完整声明，本批没出现的物品
 *       自动下线（{@link #commit(long)}）。删掉一行注册不需要写注销。</li>
 *   <li><b>归属优先</b>：条目记 {@code plugin}，卸载时强制回收，不依赖词库写清理代码。</li>
 *   <li><b>派生而非硬编码</b>：item definition、模型、创造页条目都由这里生成，
 *       贴图仍由插件 {@code 资源/物品材质/} 提供（宿主不造 PNG）。</li>
 * </ol>
 *
 * <p>物品本身仍是「原版基底 + 组件」，所以不需要往注册表塞新 {@code Item} ——
 * 词库凭空造物品是零风险的。</p>
 */
public final class ItemRegistry {
    private static final Logger LOG = LogManager.getLogger("nebula");

    private static final Map<String, ItemDef> BY_ID = new LinkedHashMap<>();
    /** 标记 → 物品 id： 允许只写  这种短名。 */
    private static final Map<String, String> BY_MARKER = new LinkedHashMap<>();
    private static final Set<String> BATCH_PLUGINS = new LinkedHashSet<>();

    private ItemRegistry() {
    }

    /** 一件物品的定义（字段名与词库对象一一对应）。 */
    public record ItemDef(String id, String plugin, JsonObject def, long batch) {
    }

    public static synchronized ItemDef byId(String id) {
        return BY_ID.get(id);
    }

    public static synchronized boolean isRegistered(String id) {
        return BY_ID.containsKey(id);
    }

    /** 动作落地：新增或更新一件物品。 */
    public static synchronized void upsert(JsonObject action) {
        String id = str(action, "物品", "");
        if (id.isBlank()) {
            return;
        }
        String plugin = str(action, "插件", "");
        JsonObject def = action.has("定义") && action.get("定义").isJsonObject()
                ? action.getAsJsonObject("定义") : new JsonObject();
        long batch = action.has("批次") ? action.get("批次").getAsLong() : 0L;
        BY_ID.put(id, new ItemDef(id, plugin, def, batch));
        String marker = str(def, "标记", "");
        if (!marker.isBlank()) {
            BY_MARKER.put(marker, id);
        }
        BATCH_PLUGINS.add(plugin);
    }

    /** 单件即时下线（`$剑.卸载$`）。 */
    public static synchronized void remove(String id) {
        ItemDef old = BY_ID.remove(id);
        if (old != null) {
            BY_MARKER.entrySet().removeIf(entry -> entry.getValue().equals(old.id()));
        }
    }

    /** 按 id 或标记找物品（`给物品` 两种都收）。 */
    public static synchronized ItemDef find(String idOrMarker) {
        ItemDef byId = BY_ID.get(idOrMarker);
        if (byId != null) {
            return byId;
        }
        String id = BY_MARKER.get(idOrMarker);
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * 按批次收口：本批出现过声明的插件，其名下未在本批出现的物品全部下线。
     *
     * <p>编译失败的插件不会进入 {@code BATCH_PLUGINS}（Rust 侧已经丢掉了它这一轮的动作），
     * 所以旧物品集保持不动。</p>
     */
    public static synchronized void commit(long batch) {
        if (BATCH_PLUGINS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, ItemDef>> iterator = BY_ID.entrySet().iterator();
        while (iterator.hasNext()) {
            ItemDef def = iterator.next().getValue();
            if (BATCH_PLUGINS.contains(def.plugin()) && def.batch() != batch) {
                String marker = str(def.def(), "标记", "");
                if (!marker.isBlank()) {
                    BY_MARKER.remove(marker, def.id());
                }
                iterator.remove();
                LOG.info("[星云] 物品下线：{}（插件 {} 本批未声明）", def.id(), def.plugin());
            }
        }
        BATCH_PLUGINS.clear();
    }

    /** 已注册物品的展示名（`星云剑 · 星云苹果`），供 `$世界.取物品列表$` 直接打印菜单。 */
    public static synchronized String namesText() {
        List<String> names = new ArrayList<>();
        for (ItemDef item : BY_ID.values()) {
            names.add(str(item.def(), "名称", item.id()));
        }
        return String.join(" · ", names);
    }

    /** 按物品 id 造一个带全部组件的 ItemStack；未注册返回 {@code null}。 */
    public static ItemStack stackOf(String idOrMarker, int count) {
        ItemDef def = find(idOrMarker);
        return def == null ? null : stack(def.def(), count);
    }

    /**
     * 按定义造 ItemStack（服务端给物品与客户端重建创造页共用）。
     *
     * <p>所有组件都是原版 {@code DataComponents}，不引入新注册项，因此不需要客户端装 mod
     * 之外的东西 —— 客户端本来就有这套类。</p>
     */
    public static ItemStack stack(JsonObject def, int count) {
        String base = str(def, "基底", "minecraft:paper");
        Item item = BuiltInRegistries.ITEM.get(Identifier.parse(base))
                .map(holder -> holder.value()).orElse(null);
        if (item == null) {
            LOG.warn("[星云] 物品定义：未知基底 {}", base);
            return null;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, count));

        int durability = (int) num(def, "耐久", 0);
        if (durability > 0) {
            stack.set(DataComponents.MAX_DAMAGE, durability);
            // 有耐久的物品必须单件堆叠，否则耐久条不显示。
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
        } else if (def.has("堆叠")) {
            int size = (int) num(def, "堆叠", 1);
            if (size > 0) {
                stack.set(DataComponents.MAX_STACK_SIZE, size);
            }
        }

        String model = str(def, "模型", "");
        if (!model.isBlank()) {
            stack.set(DataComponents.ITEM_MODEL, Identifier.parse(model));
        }

        // 刷怪蛋的名字必须写 ITEM_NAME 而不是 CUSTOM_NAME —— 原版 SpawnEggItem#useOn 会把
        // CUSTOM_NAME 拷给生成的生物，结果每只箱子生物都顶着「箱子生物蛋」这个名字。
        // 玩家用铁砧改名时改的仍是 CUSTOM_NAME，那条原版彩蛋（命名蛋生出命名生物）照旧。
        boolean realEgg = !str(def, "实体", "").isBlank();
        String name = str(def, "名称", "");
        if (!name.isBlank()) {
            ChatFormatting color = color(str(def, "颜色", ""));
            MutableComponent text = Component.literal(name);
            MutableComponent styled = color == null ? text : text.withStyle(color);
            stack.set(realEgg ? DataComponents.ITEM_NAME : DataComponents.CUSTOM_NAME, styled);
        }

        String rarity = str(def, "稀有度", "");
        if (!rarity.isBlank()) {
            try {
                stack.set(DataComponents.RARITY,
                        Rarity.valueOf(rarity.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 写错了就当没写
            }
        }

        List<Component> lines = lore(def);
        if (!lines.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }

        ItemAttributeModifiers modifiers = modifiers(def);
        if (modifiers != null) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
        }

        if (def.has("食物") && def.get("食物").isJsonObject()) {
            JsonObject food = def.getAsJsonObject("食物");
            stack.set(DataComponents.FOOD, new FoodProperties(
                    (int) num(food, "饥饿", 4),
                    (float) num(food, "饱和", 0),
                    bool(food, "总能吃")));
        }

        // 真刷怪蛋：基底是 nebula_bridge:spawn_egg 时，实体类型靠这个组件决定
        // （SpawnEggItem#getType 读的就是它）。有它就不再写 custom_data ——
        // 词库的 `[事件]右键方块` 只负责兜旧蛋，否则原版 useOn 生一只、事件再生一只。
        String entityId = str(def, "实体", "");
        if (!entityId.isBlank()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(entityId))
                    .map(holder -> holder.value()).orElse(null);
            if (type == null) {
                LOG.warn("[星云] 物品「{}」：实体 {} 不存在，刷怪蛋点了不会生成",
                        str(def, "名称", entityId), entityId);
            } else {
                stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, new CompoundTag()));
            }
        }

        String marker = str(def, "标记", "");
        if (!marker.isBlank() && !realEgg) {
            CompoundTag tag = new CompoundTag();
            tag.putString("nebula_id", marker);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    /**
     * 服务端创造页条目：注册了什么就给什么，全部进「星云物品」一栏。
     *
     * <p>不再按 `创造页` 字段散到原版各页 —— 词库只维护一份配置，玩家只需要看一个地方。</p>
     */
    public static synchronized List<JsonObject> creativeEntries() {
        List<JsonObject> out = new ArrayList<>();
        for (ItemDef item : BY_ID.values()) {
            // 整份定义直接当创造页条目：`CreativeItems` 用同一个 `stack()` 构造，
            // 所以描述 / 属性 / 食物在创造页里也完整。
            out.add(item.def().deepCopy());
        }
        return out;
    }

    /**
     * 资源包派生条目：item definition、物品模型、创造页声明。
     *
     * <p>只派生 JSON，<b>不造 PNG</b> —— 贴图由插件 {@code 资源/物品材质/<名>.png} 提供，
     * 缺文件时客户端是紫黑块，这是作者侧的问题，宿主不猜。</p>
     */
    public static synchronized Map<String, byte[]> entries() {
        Map<String, byte[]> out = new TreeMap<>();
        Map<String, JsonArray> creativeByPlugin = new LinkedHashMap<>();

        for (ItemDef item : BY_ID.values()) {
            JsonObject def = item.def();
            String model = str(def, "模型", "");
            if (model.isBlank()) {
                continue;
            }
            // `nebula:item/star_sword` → assets/nebula/items/item/star_sword.json
            //                      → assets/nebula/models/item/star_sword.json
            String namespace = "nebula";
            String path = model;
            int colon = model.indexOf(':');
            if (colon >= 0) {
                namespace = model.substring(0, colon);
                path = model.substring(colon + 1);
            }
            if (path.isBlank()) {
                continue;
            }
            out.put("assets/" + namespace + "/items/" + path + ".json", utf8(itemDefinition(model)));
            out.put("assets/" + namespace + "/models/" + path + ".json", utf8(itemModel(model)));

            creativeByPlugin
                    .computeIfAbsent(item.plugin(), key -> new JsonArray())
                    .add(def.deepCopy());
        }
        for (Map.Entry<String, JsonArray> entry : creativeByPlugin.entrySet()) {
            out.put("assets/nebula/creative/item_" + slug(entry.getKey()) + ".json",
                    utf8(entry.getValue().toString()));
        }
        return out;
    }

    // ────────────────────────── 内部 ──────────────────────────

    private static List<Component> lore(JsonObject def) {
        List<Component> lines = new ArrayList<>();
        if (!def.has("描述") || !def.get("描述").isJsonArray()) {
            return lines;
        }
        for (JsonElement element : def.getAsJsonArray("描述")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject line = element.getAsJsonObject();
            ChatFormatting tint = color(str(line, "颜色", ""));
            MutableComponent text = Component.literal(str(line, "文本", ""));
            lines.add(tint == null ? text : text.withStyle(tint));
        }
        return lines;
    }

    private static ItemAttributeModifiers modifiers(JsonObject def) {
        if (!def.has("属性") || !def.get("属性").isJsonArray()) {
            return null;
        }
        List<ItemAttributeModifiers.Entry> list = new ArrayList<>();
        for (JsonElement element : def.getAsJsonArray("属性")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject one = element.getAsJsonObject();
            String attributeId = str(one, "属性", "");
            if (attributeId.isBlank()) {
                continue;
            }
            Holder<Attribute> attribute = BuiltInRegistries.ATTRIBUTE
                    .get(Identifier.parse(attributeId)).orElse(null);
            if (attribute == null) {
                LOG.warn("[星云] 物品属性：未知属性 {}", attributeId);
                continue;
            }
            AttributeModifier.Operation operation;
            try {
                operation = AttributeModifier.Operation.valueOf(
                        str(one, "运算", "add_value").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOG.warn("[星云] 物品属性：未知运算 {}", str(one, "运算", ""));
                continue;
            }
            String modifierId = str(one, "id", "nebula:modifier");
            EquipmentSlotGroup slot = slotGroup(str(one, "槽位", "mainhand"));
            list.add(new ItemAttributeModifiers.Entry(
                    attribute,
                    new AttributeModifier(Identifier.parse(modifierId), num(one, "数值", 0), operation),
                    slot));
        }
        return list.isEmpty() ? null : new ItemAttributeModifiers(list);
    }

    private static EquipmentSlotGroup slotGroup(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "mainhand", "主手" -> EquipmentSlotGroup.MAINHAND;
            case "offhand", "副手" -> EquipmentSlotGroup.OFFHAND;
            case "hand", "手" -> EquipmentSlotGroup.HAND;
            case "head", "头盔" -> EquipmentSlotGroup.HEAD;
            case "chest", "胸甲" -> EquipmentSlotGroup.CHEST;
            case "legs", "护腿" -> EquipmentSlotGroup.LEGS;
            case "feet", "靴子" -> EquipmentSlotGroup.FEET;
            case "armor", "护甲" -> EquipmentSlotGroup.ARMOR;
            case "body", "身体" -> EquipmentSlotGroup.BODY;
            default -> EquipmentSlotGroup.ANY;
        };
    }

    private static ChatFormatting color(String name) {
        if (name.isBlank()) {
            return null;
        }
        try {
            return ChatFormatting.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 1.21.4+ 的 item definition：把 item_model 指到一个模型文件。 */
    private static String itemDefinition(String model) {
        JsonObject root = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", model);
        root.add("model", inner);
        return root.toString();
    }

    private static String itemModel(String model) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", model);
        root.add("textures", textures);
        return root.toString();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 文件名安全化。中文插件名会被整段替换成下划线，那样两个中文插件会撞成同一个文件名，
     * 所以纯下划线的结果再挂上原名的哈希（稳定，重打不会变）。
     */
    private static String slug(String text) {
        String out = text.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
        if (out.replace("_", "").isEmpty()) {
            out = "p" + Integer.toHexString(text.hashCode());
        }
        return out;
    }

    private static String str(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static double num(JsonObject object, String key, double fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException e) {
            try {
                return Double.parseDouble(value.getAsString().trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return false;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            String text = value.getAsString().trim();
            return "是".equals(text) || "true".equalsIgnoreCase(text);
        }
    }
}
