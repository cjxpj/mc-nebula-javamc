package nebula.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 词库驱动的箱子界面。
 *
 * <p>词库先 {@code $面:$世界.创建界面 玩家 行数 标题$} 拿到界面对象 id，之后
 * {@code $世界.界面物品 面 槽位 …$} / {@code $世界.界面数据 面 …$} 都针对这个对象操作，
 * 最后 {@code $世界.打开界面 面 回调$} 把界面和点击闭包一起交给宿主。点击不移动任何物品：
 * {@link NebulaMenu#clicked} 拦下所有点击，把「谁点了第几格」作为 JSON 交给 Rust 侧
 * 该界面保存的闭包处理，不再走事件广播。</p>
 *
 * <p>每个玩家一份 {@link Session}，一直留到退出游戏；关闭界面只清掉 menu 引用，容器内容保留到
 * 下一次 {@code 清空界面}。这样同一个 tick 内「关旧界面 → 开新界面」不会丢物品。</p>
 */
public final class GuiManager {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private GuiManager() {
    }

    static final class Session {
        final String plugin;
        String title = "";
        int rows = 3;
        SimpleContainer container = new SimpleContainer(27);
        NebulaMenu menu;
        /** 当前界面对象 id；点击时交给 Rust 找回该界面的闭包。 */
        long guiId;
        /** 点击事件原样回传的临时数据（兼容旧词库）。 */
        String data = "";
        /** 行数变化后需要重新打开菜单。 */
        boolean reopen;

        Session(String plugin) {
            this.plugin = plugin;
        }
    }

    private static Session session(ServerPlayer player, String plugin) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.plugin.equals(plugin)) {
            session = new Session(plugin);
            SESSIONS.put(player.getUUID(), session);
        }
        return session;
    }

    /** 清空界面并设定行数；界面已打开且行数相同时原地清空，不闪屏。 */
    static void clear(ServerPlayer player, String plugin, int rows) {
        Session session = session(player, plugin);
        int want = Math.max(1, Math.min(rows, 6));
        boolean open = session.menu != null && player.containerMenu == session.menu;
        if (open && session.rows != want) {
            session.rows = want;
            session.container = new SimpleContainer(want * 9);
            session.reopen = true;
        } else if (open) {
            session.container.clearContent();
        } else {
            session.rows = want;
            session.container = new SimpleContainer(want * 9);
        }
        session.data = "";
        if (open) {
            session.menu.broadcastFullState();
        }
    }

    /** 往某个槽位放展示物品；界面已打开时立即同步到客户端。 */
    static void setItem(ServerPlayer player, String plugin, int slot, String itemId, int count,
            String name, String lore, boolean glow) {
        Session session = session(player, plugin);
        if (slot < 0 || slot >= session.container.getContainerSize()) {
            return;
        }
        session.container.setItem(slot, buildStack(itemId, count, name, lore, glow));
    }

    static void setData(ServerPlayer player, String plugin, String data) {
        session(player, plugin).data = data == null ? "" : data;
    }

    /**
     * 打开界面对象。标题与行数都没变且界面已打开时只换掉绑定的界面 id，不重新开菜单，
     * 词库可以把「重绘」写成 创建 → 设物品 → 打开 三步，切页不会闪。
     */
    static void open(ServerPlayer player, String plugin, String title, long guiId) {
        Session session = session(player, plugin);
        String wanted = title == null || title.isEmpty() ? session.title : title;
        boolean reopenNeeded = session.menu == null || player.containerMenu != session.menu
                || session.reopen || !wanted.equals(session.title);
        session.guiId = guiId;
        if (!reopenNeeded) {
            // 菜单不重开，只把同一个菜单对象换绑到新界面（重绘时闭包随之更新）。
            session.menu.guiId = guiId;
            return;
        }
        session.title = wanted;
        session.reopen = false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Runnable task = () -> openNow(player, session, guiId);
        if (server == null) {
            task.run();
        } else {
            // 点击回调里直接 openMenu 会在旧菜单的 clicked() 里关旧开新，放到下一 tick 更稳。
            server.execute(task);
        }
    }

    private static void openNow(ServerPlayer player, Session session, long guiId) {
        if (player.hasDisconnected()) {
            return;
        }
        String title = session.title.isEmpty() ? "星云界面" : session.title;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
            NebulaMenu menu = new NebulaMenu(typeFor(session.rows), id, inventory,
                    session.container, session.rows, session, guiId);
            session.menu = menu;
            return menu;
        }, Component.literal(title)));
    }

    static void close(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        notifyClosed(session.guiId);
        if (session.menu != null && player.containerMenu == session.menu) {
            player.closeContainer();
        }
    }

    /** 玩家用 Esc / 指令关掉界面：只断引用，容器留给下一次重绘。 */
    static void onClose(ServerPlayer player, AbstractContainerMenu menu) {
        // 按菜单自己绑定的 id 回收：重绘换 id / 换插件时也不会误删新界面的闭包。
        if (menu instanceof NebulaMenu nebula) {
            notifyClosed(nebula.guiId);
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.menu == menu) {
            session.menu = null;
        }
    }

    static void onLogout(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            notifyClosed(session.guiId);
        }
    }

    private static void notifyClosed(long guiId) {
        long handle = NebulaMod.handle();
        if (handle == 0L || guiId <= 0L) {
            return;
        }
        try {
            NativeBridge.closeGui(handle, guiId);
        } catch (Throwable t) {
            NebulaMod.log().warn("[星云] 界面对象回收失败", t);
        }
    }

    static void clearAll() {
        SESSIONS.clear();
    }

    private static MenuType<?> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
    }

    private static ItemStack buildStack(String itemId, int count, String name, String lore, boolean glow) {
        Item item = McApi.item(itemId);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(count, max)));
        if (name != null && !name.isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore.split("\\|")) {
                lines.add(Component.literal(line));
            }
            if (!lines.isEmpty()) {
                stack.set(DataComponents.LORE, new ItemLore(lines));
            }
        }
        if (glow) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private static void dispatchClick(Session session, ServerPlayer player, long guiId, int slot,
            int button, ClickType type, ItemStack stack) {
        long handle = NebulaMod.handle();
        if (handle == 0L) {
            return;
        }
        JsonObject payload = NebulaMod.playerProfile(player);
        payload.addProperty("玩家名", player.getGameProfile().name());
        payload.addProperty("界面", session.title);
        payload.addProperty("槽位", slot);
        payload.addProperty("按钮", button);
        payload.addProperty("动作", actionLabel(type, button));
        payload.addProperty("区域", slot < session.rows * 9 ? "界面" : "背包");
        payload.addProperty("物品", stack.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        payload.addProperty("数量", stack.getCount());
        payload.addProperty("数据", session.data);
        if (guiId <= 0L) {
            NebulaMod.log().warn("[星云] 界面点击缺少界面对象 id，已忽略（请用 创建界面 + 打开界面）");
            return;
        }
        try {
            String raw = NativeBridge.clickGui(handle, guiId, payload.toString());
            JsonObject result = NebulaMod.parse(raw);
            NebulaMod.afterDispatch(player, result);
        } catch (Throwable t) {
            NebulaMod.log().error("[星云] 界面点击分发失败", t);
        }
    }

    private static String actionLabel(ClickType type, int button) {
        return switch (type) {
            case QUICK_MOVE -> button == 0 ? "Shift左键" : "Shift右键";
            case SWAP -> "换手";
            case CLONE -> "中键";
            case THROW -> "丢弃";
            case PICKUP_ALL -> "双击";
            default -> button == 1 ? "右键" : "左键";
        };
    }

    /**
     * 只读箱子菜单：所有点击都不走原版搬运逻辑，只上报事件。
     *
     * <p>客户端会先本地预测一次搬运，所以处理完点击立即发一次全量槽位同步，把光标上的
     * 预测物品清掉。</p>
     */
    static final class NebulaMenu extends ChestMenu {
        private final Session session;
        /** 当前绑定的界面对象；重绘换界面时由 {@link GuiManager#open} 更新。 */
        long guiId;

        NebulaMenu(MenuType<?> type, int id, Inventory inventory, Container container, int rows,
                Session session, long guiId) {
            super(type, id, inventory, container, rows);
            this.session = session;
            this.guiId = guiId;
        }

        @Override
        public void clicked(int slotId, int button, ClickType type, Player player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 拖拽会连发一串包，不当作点击。
            if (type == ClickType.QUICK_CRAFT) {
                broadcastFullState();
                return;
            }
            if (slotId < 0) {
                // 点窗口外：客户端会预测「丢弃光标物品」，回一次全量状态纠正。
                broadcastFullState();
                return;
            }
            if (slotId >= this.slots.size()) {
                return;
            }
            ItemStack stack = this.slots.get(slotId).getItem();
            dispatchClick(session, serverPlayer, guiId, slotId, button, type, stack);
            if (serverPlayer.containerMenu == this) {
                broadcastFullState();
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }
}
