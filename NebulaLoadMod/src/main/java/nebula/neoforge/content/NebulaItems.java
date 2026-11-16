package nebula.neoforge.content;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 通用物品池：目前只有一个「通用刷怪蛋」。
 *
 * <p><b>为什么一个物品能吃下所有品种的蛋</b>：1.21 的 {@link SpawnEggItem} 构造器只收
 * {@link Item.Properties}，实体类型是 <b>ItemStack 的 {@code entity_data} 组件</b>决定的
 * （{@code SpawnEggItem#getType(ItemStack)} 读的就是 {@code DataComponents.ENTITY_DATA}）。
 * 这正是原版自己的做法 —— {@code minecraft:pig_spawn_egg}、{@code cow_spawn_egg} 都是
 * 「带不同 {@code entity_data} 的同一个 SpawnEggItem 类」。</p>
 *
 * <p>所以这里只注册一个 id，词库加品种时不需要新增注册项；每个 ItemStack 由
 * {@link ItemRegistry#stack} 塞入 {@code entity_data} + {@code item_model}，
 * 于是既能生对生物，也有各自的外观。</p>
 *
 * <p>既然是真正的 {@code SpawnEggItem}，它就自动进原版「生成蛋」页，
 * 并且白拿原版全套行为：落点算法、{@code CUSTOM_NAME} 传递、冷却、音效、发射器、繁殖。</p>
 */
public final class NebulaItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("nebula_bridge");

    /** 通用刷怪蛋。默认模型指向原版鸡蛋，避免忘设 {@code item_model} 时是紫黑块。 */
    public static final DeferredItem<Item> SPAWN_EGG =
            ITEMS.registerItem("spawn_egg", SpawnEggItem::new, props -> props);

    private NebulaItems() {
    }

    /**
     * 资源包派生条目：通用刷怪蛋的 client item 定义。
     *
     * <p>默认模型指 {@code minecraft:item/egg}（原版鸡蛋）—— 每个蛋的 ItemStack 都会用
     * {@code item_model} 组件覆盖成自己的外观，这条只是兜底，别让没设组件的蛋变紫黑块。</p>
     */
    public static Map<String, byte[]> entries() {
        Map<String, byte[]> out = new TreeMap<>();
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", "minecraft:item/egg");
        JsonObject definition = new JsonObject();
        definition.add("model", model);
        out.put("assets/nebula_bridge/items/spawn_egg.json",
                definition.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }
}

