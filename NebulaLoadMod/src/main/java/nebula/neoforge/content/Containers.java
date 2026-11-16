package nebula.neoforge.content;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import nebula.neoforge.DropStack;

/**
 * 掉落物 → 容器 的唯一实现。
 *
 * <p>这里只有「怎么装」：同类先合并 → 再填空格 → 塞不下的留在地上，
 * 装完回写掉落物（全装完删除实体，装一半写余量），不复制不刷物品。</p>
 *
 * <p>「什么时候装、装哪一颗、走不过去怎么办」都算策略，在词库
 * （{@code nebula/<插件>/main.n}）里，或者直接调 {@code $世界.巡逻 实体$} 用默认策略。</p>
 */
public final class Containers {
    private Containers() {
    }

    /** 往容器里塞，返回没塞进去的余量（不改动入参）。 */
    public static ItemStack insert(Container container, ItemStack stack) {
        ItemStack rest = stack.copy();
        int size = container.getContainerSize();
        // 第一轮：并进同类未满堆叠
        for (int i = 0; i < size && !rest.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, rest)) {
                continue;
            }
            int room = slot.getMaxStackSize() - slot.getCount();
            if (room <= 0 || !container.canPlaceItem(i, rest)) {
                continue;
            }
            int move = Math.min(room, rest.getCount());
            slot.grow(move);
            container.setItem(i, slot);
            rest.shrink(move);
        }
        // 第二轮：占空格
        for (int i = 0; i < size && !rest.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, rest)) {
                continue;
            }
            int move = Math.min(rest.getMaxStackSize(), rest.getCount());
            ItemStack placed = rest.copy();
            placed.setCount(move);
            container.setItem(i, placed);
            rest.shrink(move);
        }
        if (rest.getCount() != stack.getCount()) {
            container.setChanged();
        }
        return rest;
    }

    /**
     * 把一只掉落物塞进容器，返回实际装进去的件数（0 = 一格都塞不下）。
     *
     * <p><b>按「逻辑堆」的真实总量取，不是按实体里那 64 个：</b>
     * 掉落物堆叠插件把超过上限的堆记成实体 64 + {@code nebula_total}（见 {@link DropStack}）。
     * 只看 {@code getItem().getCount()} 的话，一堆 300 个石头只会被取走 64 个，
     * 然后把实体丢掉——剩下的 236 个凭空消失。</p>
     *
     * <p>所以这里按「一批一批往容器里塞」的方式循环，塞不动就停；装完把剩余量写回逻辑堆。</p>
     */
    public static int absorb(Entity owner, Container container, ItemEntity drop) {
        ItemStack template = drop.getItem();
        if (template.isEmpty()) {
            return 0;
        }
        long remaining = DropStack.totalOf(drop);
        int moved = 0;
        while (remaining > 0) {
            int batch = (int) Math.min(remaining, template.getMaxStackSize());
            ItemStack left = insert(container, template.copyWithCount(batch));
            int took = batch - left.getCount();
            if (took <= 0) {
                break; // 一格都塞不下
            }
            moved += took;
            remaining -= took;
            if (!left.isEmpty()) {
                break; // 容器只剩半格空间，剩下的留在原地
            }
        }
        if (moved <= 0) {
            return 0;
        }
        if (remaining <= 0) {
            drop.discard();
        } else {
            DropStack.setTotal(drop, remaining);
        }
        owner.playSound(SoundEvents.ITEM_PICKUP, 0.2F, 1.0F);
        return moved;
    }
}
