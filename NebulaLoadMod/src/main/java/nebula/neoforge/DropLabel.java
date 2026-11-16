package nebula.neoforge;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 掉落物数量显示。
 *
 * <p>直接写物品实体自己的 {@code CustomName} + {@code CustomNameVisible}：1.21.9 起重构后的
 * {@code ItemEntityRenderer.submit} 会回调 {@code EntityRenderer.submit}，后者提交名牌，
 * 判定只看 {@code Entity#isCustomNameVisible()}，所以掉在地上的物品能显示名字。
 *
 * <p>好处是名牌就是实体字段本身：物品被拾取、吸入漏斗、掉进虚空、被合并，名字跟着实体一起消失，
 * <b>不可能留下孤儿显示实体</b>；也不生额外实体、不发命令、不写物品栈，拾取/漏斗/水流/分类器
 * 全部按原版逻辑走。
 *
 * <p><b>一片地上只挂一个名牌</b>：同物品 + 同组件 + 同主人的一堆掉落物里，只有总量最大的那颗
 * （并列时实体 id 小的）显示数量，其余不挂。这样被聚拢的小堆不会一路挂着数字走过来，
 * 也不会出现"三个 5 堆 → 三个名牌"的糊屏。
 *
 * <p>只在数量或状态变化时重写组件，每 10 tick 扫一次已加载实体，平时零开销。名字带
 * {@code nebula_label} 持久标记，只清理自己写的名字，不碰玩家手动命名的实体。
 *
 * <p>开关来自词库插件写入的宿主环境变量 {@code nebula_dropstack_display}：{@code 是} 才显示。
 */
public final class DropLabel {
    /** 10 tick = 0.5 秒。 */
    private static final int INTERVAL = 10;
    /** 上一版（text_display 方案）留下的全息字标签，见到就清掉。 */
    private static final String LEGACY_DISPLAY_TAG = "nebula_drop_label";
    /** 本类写过的名字标记，值是写入时的数量；只认这个标记，避免清掉别人起的名字。 */
    private static final String MARK = "nebula_label";

    private static int ticks;

    private DropLabel() {
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++ticks < INTERVAL) {
            return;
        }
        ticks = 0;
        boolean enabled = "是".equals(McSupport.env("nebula_dropstack_display"));
        for (ServerLevel level : server.getAllLevels()) {
            List<ItemEntity> items = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                // 旧版全息字：区块一加载就清，避免世界升级后留下孤儿 display。
                if (entity.getTags().contains(LEGACY_DISPLAY_TAG)) {
                    entity.discard();
                    continue;
                }
                if (entity instanceof ItemEntity item) {
                    items.add(item);
                }
            }
            applyLevel(items, enabled);
        }
    }

    /**
     * 先按「同物品 + 同主人」分组，再让每组里的主堆挂名牌。
     *
     * <p>只有**可能挂名牌**的实体参与主堆比较：数量 1（零散掉落物占绝大多数）、带数据组件、
     * 带 {@code nebula_nostack} 的一律不进组。否则一片农田上几百颗单件掉落物会两两比一遍，
     * 白白 O(n²)。其余实体仍然逐个走 {@link #apply}，只为了把过期名牌清掉。</p>
     */
    private static void applyLevel(List<ItemEntity> items, boolean enabled) {
        if (!enabled) {
            for (ItemEntity item : items) {
                apply(item, false);
            }
            return;
        }
        // 每一堆都标自己的数量：以前只给「同一片地里最大的那堆」挂名牌（避免糊屏），
        // 但玩家看到的是「明明三堆东西只有一堆有数字」，反而以为数量丢了。现在只要 labelable 就标。
        for (ItemEntity item : items) {
            apply(item, labelable(item));
        }
    }

    /** 值不值得挂名牌：数量 &gt;1、无数据组件、没有 nebula_nostack 标签。 */
    private static boolean labelable(ItemEntity item) {
        ItemStack stack = item.getItem();
        return !item.isRemoved()
                && !stack.isEmpty()
                && DropStack.total(item) > 1
                && stack.isComponentsPatchEmpty()
                && !item.getTags().contains("nebula_nostack");
    }

    /** {@code want} 已经算好（能挂 + 是主堆 + 开关打开），这里只管把名牌字段写成 / 擦掉。 */
    private static void apply(ItemEntity item, boolean want) {
        CompoundTag data = item.getPersistentData();
        boolean marked = data.contains(MARK);
        ItemStack stack = item.getItem();
        long count = DropStack.total(item);

        // 别人起的名字一律不动：只覆盖自己写过的（marked），其余靠 nebula_nostack 规避。
        if (want && !marked && item.hasCustomName()) {
            return;
        }

        if (!want) {
            if (marked) {
                item.setCustomName(null);
                item.setCustomNameVisible(false);
                data.remove(MARK);
            }
            return;
        }
        // 数量没变、可见性也正常时不重写，避免每轮都发一次 metadata 包。
        if (marked && data.getLong(MARK).orElse(-1L) == count && item.isCustomNameVisible()) {
            return;
        }
        item.setCustomName(label(stack, count));
        item.setCustomNameVisible(true);
        data.putLong(MARK, count);
    }

    /**
     * 立刻把名牌写上——给「逻辑堆被漏斗吸空后重建」用。
     *
     * <p>新实体要等下一轮 10 tick 扫描才会挂名字，中间这段时间玩家看到的就是「×700 闪没了」。
     * 重建时马上写一次，视觉上就只剩实体本身的那一 tick 空档。</p>
     */
    public static void nameNow(ItemEntity item, long total) {
        if (item.getItem().isEmpty() || total <= 1) {
            return;
        }
        item.setCustomName(label(item.getItem(), total));
        item.setCustomNameVisible(true);
        item.getPersistentData().putLong(MARK, total);
    }

    /**
     * {@code 钻石 ×3}：物品名保留自己的稀有度颜色（白/蓝/金），数量部分固定黄色。
     * 用 {@link ItemStack#getItemName()} 而不是 {@code getName()}，避免把实体上已有的名字拼进来。
     */
    private static Component label(ItemStack stack, long count) {
        return Component.empty()
                .append(stack.getItemName())
                .append(Component.literal(" ×" + count).withStyle(ChatFormatting.YELLOW));
    }
}
