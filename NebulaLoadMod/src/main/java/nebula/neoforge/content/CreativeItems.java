package nebula.neoforge.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 创造页填充器：按声明里的 {@code 页} 把物品投到对应标签。
 *
 * <p>原版创造页只列**已注册物品**，而星云的自定义物品都是「原版基底 + 组件」，
 * 天生不会出现在页里。这里把 {@link ItemRegistry} 注册的条目逐个构造成 ItemStack，
 * 按 {@code 页} 投递 —— 写「生成蛋」的进原版生成蛋页（刷怪蛋就该在这一页），
 * 其余进 {@link NebulaCreativeTab} 那一栏。词库加一件物品，页里就多一件，不用改 Java。</p>
 *
 * <p><b>创造页是客户端本地重建的</b>（{@code CreativeModeInventoryScreen#tryRebuildTabContents}
 * → {@code CreativeModeTab#buildContents} → {@code EventHooks.onCreativeModeTabBuildContents}），
 * 客户端机器的 gameDir 里没有服务端的 {@code nebula/} 目录，所以客户端必须从<b>资源包</b>读声明
 * （{@code assets/nebula/creative/*.json}，{@code NebulaPack} 打的副本）。</p>
 */
public final class CreativeItems {
    private static final Logger LOG = LogManager.getLogger("nebula");
    /** 每个标签首次成功注入时打一行日志，之后重建创造页不再刷。 */
    private static final Set<ResourceKey<CreativeModeTab>> LOGGED = new HashSet<>();

    private CreativeItems() {
    }

    public static void add(BuildCreativeModeTabContentsEvent event) {
        List<JsonObject> all = declarations();
        int added = 0;
        for (JsonObject entry : all) {
            if (!tabFor(entry).equals(event.getTabKey())) {
                continue;
            }
            // 真刷怪蛋（SpawnEggItem）会被原版生成蛋页自动收录，这里只在它没收录时补一条 ——
            // 否则页里会出现两只：一只原版默认外观、一只我们带 item_model 的。
            if (isRealEgg(entry) && pageHasEgg(event)) {
                continue;
            }
            ItemStack stack = ItemRegistry.stack(entry, 1);
            if (stack != null) {
                event.accept(stack);
                added++;
            }
        }
        if (added > 0 && LOGGED.add(event.getTabKey())) {
            LOG.info("[星云] 创造页 {}：{} 件（{}）", event.getTabKey().identifier(), added,
                    FMLEnvironment.getDist() == Dist.CLIENT ? "客户端：资源包" : "服务端：注册表");
        }
    }

    /** 这条声明是不是走真 {@code SpawnEggItem} 的刷怪蛋（基底被宿主改写成了通用蛋）。 */
    private static boolean isRealEgg(JsonObject entry) {
        JsonElement base = entry.get("基底");
        return base != null && !base.isJsonNull()
                && "nebula_bridge:spawn_egg".equals(base.getAsString());
    }

    /** 原版生成蛋页里是不是已经有通用刷怪蛋了。 */
    private static boolean pageHasEgg(BuildCreativeModeTabContentsEvent event) {
        for (ItemStack existing : event.getParentEntries()) {
            if (existing.is(NebulaItems.SPAWN_EGG.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 声明里的 {@code 页} → 目标创造页。
     *
     * <p>只认两个值：{@code 生成蛋}（也接受 {@code spawn_eggs}）进原版生成蛋页，
     * 其余一律进「星云物品」。想投别的原版页再加映射，别让词库写 tab id 字符串 ——
     * 那个 id 是原版内部资源键，写错了是静默不显示。</p>
     */
    private static ResourceKey<CreativeModeTab> tabFor(JsonObject entry) {
        JsonElement page = entry.get("页");
        String value = page == null || page.isJsonNull() ? "" : page.getAsString();
        if ("生成蛋".equals(value) || "spawn_eggs".equalsIgnoreCase(value)) {
            return CreativeModeTabs.SPAWN_EGGS;
        }
        return NebulaCreativeTab.ITEMS_KEY;
    }

    /** 客户端读资源包里的派生声明；服务端直接读运行期注册表。 */
    static List<JsonObject> declarations() {
        List<JsonObject> out = new ArrayList<>();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            for (String text : clientDeclTexts()) {
                absorb(text, out, "资源包");
            }
            return out;
        }
        out.addAll(ItemRegistry.creativeEntries());
        return out;
    }

    /** 把一段声明文本（数组或单对象）摊平进 {@code out}。 */
    private static void absorb(String text, List<JsonObject> out, String source) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonArray()) {
                for (JsonElement one : parsed.getAsJsonArray()) {
                    if (one.isJsonObject()) {
                        out.add(one.getAsJsonObject());
                    }
                }
            } else if (parsed.isJsonObject()) {
                out.add(parsed.getAsJsonObject());
            }
        } catch (Exception e) {
            LOG.warn("[星云] {} 解析失败：{}", source, e.toString());
        }
    }

    /**
     * 客户端专用：读资源包里的创造页声明。
     *
     * <p>用反射调用 {@code nebula.neoforge.client.ClientCreativeDecls}，因为主源集（服务端也要编译）
     * 不能依赖客户端类；只有 dist 是 CLIENT 时才会走到这里。</p>
     */
    private static List<String> clientDeclTexts() {
        try {
            Class<?> type = Class.forName("nebula.neoforge.client.ClientCreativeDecls");
            Object result = type.getMethod("load").invoke(null);
            if (result instanceof List<?> list) {
                List<String> texts = new ArrayList<>(list.size());
                for (Object one : list) {
                    if (one instanceof String text) {
                        texts.add(text);
                    }
                }
                return texts;
            }
        } catch (ClassNotFoundException ignored) {
            // 纯服务端构建没有客户端类
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[星云] 读取资源包内的创造页声明失败：{}", e.toString());
        }
        return List.of();
    }
}
