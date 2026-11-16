package nebula.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * 掉落物逻辑无限堆。
 *
 * <p>ItemStack 的数量受 1.21.11 存盘 codec 限制（1~99），所以「无限」不写进物品栈：
 * 实体只保留一个满堆作为可见栈，真实总量记在实体持久数据里。名牌显示总量，玩家拾取时按
 * 背包容量分批发放，漏斗每次仍然搬走一个满堆。带数据组件的物品不参与，保持原版行为。</p>
 */
public final class DropStack {
    /** 10 tick 扫一次，与数量名牌同频。 */
    private static final int INTERVAL = 10;
    /** 逻辑堆标记，指令层可用 tag=nebula_pile 识别。 */
    private static final String TAG = "nebula_pile";
    private static final String NOSTACK = "nebula_nostack";
    private static final String KEY_TOTAL = "nebula_total";
    private static final String KEY_PHYS = "nebula_phys";
    private static final String KEY_ITEM = "nebula_item";
    /** 词库插件写入的开关：是 才创建/合并逻辑堆。 */
    private static final String ENV_UNLIMITED = "nebula_dropstack_unlimited";

    /** 聚拢时每轮最多挪多少格：看得见"小的走过来"，又不是瞬移。 */
    private static final double STEP = 1.5;
    /** 水平距离进到这个范围就直接并进主堆（宿主 merge 是 0.5，这里略大一点收尾）。 */
    private static final double ABSORB = 0.75;
    /** 竖直相差超过这个值就不拉：上下两层楼之间不该互相吸。 */
    private static final double MAX_DY = 1.5;

    /** 漏斗搬空可见栈时实体会被立即移除，这里记下原物品，下一拍原地补回剩余总量。 */
    private static final List<Respawn> PENDING = new ArrayList<>();

    private static int ticks;

    private record Respawn(ServerLevel level, double x, double y, double z, ItemStack template, long total, UUID owner) {
    }

    private DropStack() {
    }

    static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        flush();
        if (++ticks < INTERVAL) {
            return;
        }
        ticks = 0;
        boolean unlimited = "是".equals(McSupport.env(ENV_UNLIMITED));
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> entities = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                entities.add(entity);
            }
            for (Entity entity : entities) {
                if (entity instanceof ItemEntity item && !item.isRemoved()) {
                    maintain(item);
                }
            }
            if (unlimited) {
                merge(level);
            }
        }
    }

    static void clear() {
        PENDING.clear();
        ticks = 0;
    }

    /** 逻辑堆的总量；未标记的堆就是物品栈本身的数量。 */
    static long total(ItemEntity item) {
        CompoundTag data = item.getPersistentData();
        if (data.contains(KEY_TOTAL)) {
            return Math.max(data.getLongOr(KEY_TOTAL, 0L), item.getItem().getCount());
        }
        return item.getItem().getCount();
    }

    /**
     * 逻辑堆的真实总量（没有逻辑堆时就是物品栈本身的数量）。
     *
     * <p>拾取方必须用这个，不能只看 {@code item.getItem().getCount()}——
     * 超过上限的堆在实体里只放 64 个，真数在 {@code nebula_total} 里。</p>
     */
    public static long totalOf(ItemEntity item) {
        return total(item);
    }

    /** 回写剩余总量（{@code total <= 上限} 时会自动清掉逻辑堆标记）。 */
    public static void setTotal(ItemEntity item, long total) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty()) {
            return;
        }
        store(item, stack, Math.max(1L, total));
    }

    /** 把外部对可见栈的增减（漏斗搬走 / 指令改数量）同步进总量。 */
    private static void maintain(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag data = item.getPersistentData();
        if (!data.contains(KEY_TOTAL)) {
            return;
        }
        long total = data.getLongOr(KEY_TOTAL, stack.getCount());
        int last = data.getIntOr(KEY_PHYS, stack.getCount());
        int delta = stack.getCount() - last;
        if (delta != 0) {
            total = Math.max(1L, total + delta);
        }
        store(item, stack, total);
    }

    /** 写入总量并维持可见栈：超过上限时固定为满堆（同时让原版合并跳过它）。 */
    private static void store(ItemEntity item, ItemStack template, long total) {
        int max = Math.max(1, template.getMaxStackSize());
        CompoundTag data = item.getPersistentData();
        if (total <= max) {
            data.remove(KEY_TOTAL);
            data.remove(KEY_PHYS);
            data.remove(KEY_ITEM);
            item.removeTag(TAG);
            if (item.getItem().getCount() != total) {
                item.setItem(template.copyWithCount((int) total));
            }
            return;
        }
        data.putLong(KEY_TOTAL, total);
        data.putInt(KEY_PHYS, max);
        data.putString(KEY_ITEM, BuiltInRegistries.ITEM.getKey(template.getItem()).toString());
        item.addTag(TAG);
        if (item.getItem().getCount() != max) {
            item.setItem(template.copyWithCount(max));
        }
    }

    /** 只合并无数据组件的同类堆，避免把附魔 / 命名 / 容器内容揉在一起。 */
    private static void merge(ServerLevel level) {
        List<ItemEntity> seeds = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && !item.isRemoved() && isSeed(item)) {
                seeds.add(item);
            }
        }
        // 和聚拢同一条规则：总量大的当主堆，小的并进去（否则谁先被遍历到谁活下来）。
        seeds.sort(Comparator.comparingLong(DropStack::total).reversed()
                .thenComparingInt(Entity::getId));
        for (ItemEntity pile : seeds) {
            if (pile.isRemoved()) {
                continue;
            }
            ItemStack template = pile.getItem();
            if (template.isEmpty() || template.getMaxStackSize() <= 1 || !template.isComponentsPatchEmpty()) {
                continue;
            }
            AABB box = pile.getBoundingBox().inflate(0.5, 0.0, 0.5);
            List<ItemEntity> near = level.getEntitiesOfClass(ItemEntity.class, box,
                    other -> other != pile
                            && !other.isRemoved()
                            && !other.getTags().contains(NOSTACK)
                            // 玩家手动命名的掉落物不合并，避免名字被吞掉；自己写的数量名牌除外。
                            && (!other.hasCustomName() || other.getPersistentData().contains("nebula_label"))
                            && other.getItem().isComponentsPatchEmpty()
                            && ItemStack.isSameItemSameComponents(template, other.getItem())
                            && Objects.equals(pile.getTarget(), other.getTarget()));
            if (near.isEmpty()) {
                continue;
            }
            long total = total(pile);
            for (ItemEntity other : near) {
                if (other.isRemoved()) {
                    continue;
                }
                total += total(other);
                clearMark(other);
                other.discard();
            }
            store(pile, template, total);
        }
    }

    /** 已经是逻辑堆，或可见栈已满、原版不会再合它的堆。 */
    private static boolean isSeed(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty() || stack.getMaxStackSize() <= 1 || !stack.isComponentsPatchEmpty()) {
            return false;
        }
        if (isMarked(item)) {
            return true;
        }
        return stack.getCount() >= stack.getMaxStackSize();
    }

    // ────────────────────────── 词库原语：聚拢掉落物 ──────────────────────────

    /**
     * 把半径内的同类掉落物往「总量最大」的那颗聚拢，**最大的那颗原地不动**。
     *
     * <p>旧版由词库逐颗 {@code tp} 到最近邻，两个方向同时命中：大堆会去追刚掉下来的小堆，
     * 看起来就是在原地来回弹，而且永远停在被拉走的位置。这里按总量降序贪心分组——
     * 先处理的一定是这一片里最大的，它成为主堆后只进不出；后面比它小的朝它走，
     * 进到 {@link #ABSORB} 格就并进它的总量。</p>
     *
     * <p>只碰白名单里的物品，规则与词库配置一致：{@code 保护带组件} 时带任意数据组件的
     * 物品不参与；{@code 无限堆叠} 关闭时满堆留给原版合并。{@code nebula_nostack} 永不参与。</p>
     *
     * @param whitelist 物品注册名（{@code whitelist} 为空表示词库没给白名单，一个都不碰）
     * @return 本轮被挪动 / 并入的掉落物数量，仅用于调试
     */
    static int gather(ServerLevel level, Set<String> whitelist, double radius,
                      boolean protectComponents, boolean unlimited) {
        if (level == null || whitelist.isEmpty()) {
            return 0;
        }
        double limit = Math.max(0.5, Math.min(radius, 32.0));
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && !item.isRemoved()
                    && gatherable(item, whitelist, protectComponents, unlimited)) {
                candidates.add(item);
            }
        }
        if (candidates.size() < 2) {
            return 0;
        }
        // 总量降序、并列时实体 id 小的先处理：谁大谁是主堆，一样大就留先加载的那颗。
        candidates.sort(Comparator.comparingLong(DropStack::total).reversed()
                .thenComparingInt(Entity::getId));
        Map<String, List<ItemEntity>> anchors = new HashMap<>();
        int moved = 0;
        for (ItemEntity item : candidates) {
            ItemEntity anchor = nearestAnchor(anchors, item, limit);
            if (anchor == null) {
                anchors.computeIfAbsent(groupKey(item), key -> new ArrayList<>()).add(item);
                continue;
            }
            moved++;
            approach(item, anchor);
        }
        return moved;
    }

    /** 白名单物品解析：JSON 数组，也容忍逗号 / 空格分隔的注册名列表。 */
    static Set<String> parseIds(String raw) {
        Set<String> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        String text = raw.trim();
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        String id = element.getAsString().trim();
                        if (!id.isEmpty()) {
                            ids.add(id);
                        }
                    }
                }
                return ids;
            }
            if (root.isJsonPrimitive()) {
                ids.add(root.getAsString().trim());
                return ids;
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON（词库里手写成一串名字）：落到下面的分隔解析。
        }
        for (String id : text.replace("[", "").replace("]", "").split("[,，\\s]+")) {
            String name = id.replace("\"", "").trim();
            if (!name.isEmpty()) {
                ids.add(name);
            }
        }
        return ids;
    }

    private static boolean gatherable(ItemEntity item, Set<String> whitelist,
                                      boolean protectComponents, boolean unlimited) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty() || stack.getMaxStackSize() <= 1) {
            return false;
        }
        if (item.getTags().contains(NOSTACK)) {
            return false;
        }
        if (protectComponents && !stack.isComponentsPatchEmpty()) {
            return false;
        }
        if (!unlimited && !isMarked(item) && stack.getCount() >= stack.getMaxStackSize()) {
            // 无限堆叠关闭：满堆交给原版，只聚拢零散的，别把 64 堆从产线上拉走。
            return false;
        }
        return whitelist.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /** 分组键：同物品 + 同主人。组件差异在挑主堆时用 {@code isSameItemSameComponents} 精确比较。 */
    static String groupKey(ItemEntity item) {
        UUID owner = item.getTarget();
        return BuiltInRegistries.ITEM.getKey(item.getItem().getItem()) + "|"
                + (owner == null ? "" : owner.toString());
    }

    /** 同一组里离得最近、且组件完全一致的主堆。 */
    private static ItemEntity nearestAnchor(Map<String, List<ItemEntity>> anchors,
                                            ItemEntity item, double radius) {
        List<ItemEntity> bucket = anchors.get(groupKey(item));
        if (bucket == null) {
            return null;
        }
        double best = radius * radius;
        ItemEntity found = null;
        for (ItemEntity anchor : bucket) {
            if (anchor.isRemoved() || !ItemStack.isSameItemSameComponents(item.getItem(), anchor.getItem())) {
                continue;
            }
            double distance = anchor.distanceToSqr(item);
            if (distance <= best) {
                best = distance;
                found = anchor;
            }
        }
        return found;
    }

    /** 走近主堆：水平方向一步 {@link #STEP} 格，够近就直接并进总量。 */
    private static void approach(ItemEntity item, ItemEntity anchor) {
        if (Math.abs(anchor.getY() - item.getY()) > MAX_DY) {
            return;
        }
        double dx = anchor.getX() - item.getX();
        double dz = anchor.getZ() - item.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat <= ABSORB) {
            absorb(anchor, item);
            return;
        }
        double step = Math.min(flat, STEP);
        item.setPos(item.getX() + dx / flat * step, item.getY(), item.getZ() + dz / flat * step);
        item.setDeltaMovement(Vec3.ZERO);
    }

    /** 把小的并进主堆：总量累加，可见栈仍固定为一个满堆。 */
    private static void absorb(ItemEntity anchor, ItemEntity other) {
        ItemStack template = anchor.getItem().copy();
        long total = total(anchor) + total(other);
        clearMark(other);
        other.discard();
        store(anchor, template, total);
    }

    private static boolean isMarked(ItemEntity item) {
        return item.getPersistentData().contains(KEY_TOTAL);
    }

    private static void clearMark(ItemEntity item) {
        CompoundTag data = item.getPersistentData();
        data.remove(KEY_TOTAL);
        data.remove(KEY_PHYS);
        data.remove(KEY_ITEM);
        item.removeTag(TAG);
    }

    /** 逻辑堆的可见栈不够付总量时接管拾取，按背包容量分批发放。 */
    static void onPickup(ItemEntityPickupEvent.Pre event) {
        ItemEntity item = event.getItemEntity();
        if (item.isRemoved() || item.level().isClientSide() || item.hasPickUpDelay()) {
            return;
        }
        if (!isMarked(item)) {
            return;
        }
        // 先同步漏斗刚搬走的数量，避免按旧总量发放造成复制。
        maintain(item);
        if (!isMarked(item) || total(item) <= item.getItem().getCount()) {
            return;
        }
        UUID owner = item.getTarget();
        Player player = event.getPlayer();
        if (owner != null && !owner.equals(player.getUUID())) {
            return;
        }
        event.setCanPickup(TriState.FALSE);
        pickup(player, item);
    }

    private static void pickup(Player player, ItemEntity item) {
        ItemStack template = item.getItem();
        ItemStack original = template.copy();
        int max = Math.max(1, template.getMaxStackSize());
        long remaining = total(item);
        long picked = 0L;
        while (remaining > 0) {
            int size = (int) Math.min(remaining, max);
            ItemStack stack = template.copyWithCount(size);
            int before = stack.getCount();
            boolean added = player.getInventory().add(stack);
            int used = before - stack.getCount();
            picked += used;
            remaining -= used;
            if (!added || used <= 0) {
                break;
            }
        }
        if (picked > 0) {
            player.awardStat(Stats.ITEM_PICKED_UP.get(template.getItem()), (int) Math.min(picked, Integer.MAX_VALUE));
            player.onItemPickup(item);
        }
        if (remaining <= 0) {
            clearMark(item);
            item.setItem(ItemStack.EMPTY);
            item.discard();
        } else {
            store(item, template, remaining);
        }
        player.inventoryMenu.broadcastChanges();
        if (picked > 0) {
            NeoForge.EVENT_BUS.post(new ItemEntityPickupEvent.Post(player, item, original));
        }
    }

    /** 可见栈被漏斗整堆搬空时实体直接 discard，剩余总量在这里补回。 */
    static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item) || event.getLevel().isClientSide()) {
            return;
        }
        CompoundTag data = item.getPersistentData();
        if (!data.contains(KEY_TOTAL) || !item.getItem().isEmpty()) {
            return;
        }
        long remaining = data.getLongOr(KEY_TOTAL, 0L) - Math.max(1, data.getIntOr(KEY_PHYS, 1));
        String id = data.getStringOr(KEY_ITEM, "");
        Identifier key = Identifier.tryParse(id);
        Item material = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        UUID owner = item.getTarget();
        double x = item.getX();
        double y = item.getY();
        double z = item.getZ();
        clearMark(item);
        if (remaining <= 0 || material == null || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PENDING.add(new Respawn(level, x, y, z, new ItemStack(material), remaining, owner));
    }

    /** 补回被漏斗搬空后剩下的逻辑堆。 */
    private static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Respawn> pending = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Respawn respawn : pending) {
            ServerLevel level = respawn.level();
            if (level == null || level.getServer() == null) {
                continue;
            }
            int max = Math.max(1, respawn.template().getMaxStackSize());
            ItemEntity item = new ItemEntity(level, respawn.x(), respawn.y(), respawn.z(),
                    respawn.template().copyWithCount((int) Math.min(respawn.total(), max)));
            item.setNoPickUpDelay();
            if (respawn.owner() != null) {
                item.setTarget(respawn.owner());
            }
            store(item, respawn.template(), respawn.total());
            // 重建的实体等不到下一轮 10 tick 的名牌扫描，直接补上「×N」，否则视觉上会「数量消失」。
            DropLabel.nameNow(item, respawn.total());
            level.addFreshEntity(item);
        }
    }
}
