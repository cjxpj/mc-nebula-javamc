package nebula.neoforge.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 「星云物品」专属创造页标签。
 *
 * <p>注册项只有这一个——<b>标签本身</b>是编译期固定的，里面的内容全部来自
 * {@link ItemRegistry}（词库 {@code 定义/*.json} 注册什么就有什么），
 * 所以加物品仍然不用碰 Java。</p>
 *
 * <p>tab 的 key 是 {@code nebula:items}，客户端本地重建创造页时同样会触发
 * {@code BuildCreativeModeTabContentsEvent}，内容从资源包里的
 * {@code assets/nebula/creative/*.json} 读。</p>
 */
public final class NebulaCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "nebula");

    /** 词库物品统一进这一栏，不再散到原版各页。 */
    public static final ResourceKey<CreativeModeTab> ITEMS_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.parse("nebula:items"));

    static {
        TABS.register("items", () -> CreativeModeTab.builder()
                .title(Component.literal("星云物品"))
                .icon(() -> new ItemStack(Items.AMETHYST_SHARD))
                // 内容不在这里生成：`BuildCreativeModeTabContentsEvent` 会按注册表填，
                // 但生成器不能留空，否则客户端重建时拿不到回调。
                .displayItems((params, output) -> {
                })
                .build());
    }

    private NebulaCreativeTab() {
    }
}
