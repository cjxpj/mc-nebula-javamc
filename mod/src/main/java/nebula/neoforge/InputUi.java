package nebula.neoforge;

import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 铁砧输入框：原版 Java 客户端没有可用的表单 / 文本框组件，能输入任意文字的原生界面只有
 * 铁砧的命名框。这里把它当「一个输入框 + 一个确认按钮」用：
 *
 * <ul>
 *   <li>左边输入槽放一张纸，玩家在客户端的名字框里打字（每次按键都会发
 *       {@code ServerboundRenameItemPacket}，服务端落到 {@link AnvilMenu#setItemName}）。</li>
 *   <li>右侧成品格当确认按钮：点它就把当前文本回传给词库闭包，并关掉界面。</li>
 *   <li>其它槽位（输入槽 / 材料槽）只读，避免玩家把纸拿走。</li>
 * </ul>
 *
 * <p>回调沿用界面的通道：Rust 侧用 {@code 输入界面} 生成界面对象 id 并保存闭包，这里在确认时
 * 调 {@code clickGui(handle, id, payload)}，payload 是 {@code {玩家名, 文本, 动作}}。</p>
 */
public final class InputUi {
    private InputUi() {
    }

    /** 打开输入框；玩家还没完全进服时下一 tick 再开，避免掉进「正在加入」的空窗。 */
    static void open(ServerPlayer player, String title, String initial, long guiId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Runnable task = () -> {
            if (player.hasDisconnected()) {
                return;
            }
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new NebulaAnvil(id, inv, player, guiId, initial),
                    Component.literal(title == null || title.isEmpty() ? "输入" : title)));
        };
        if (server == null) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    /** 玩家用 Esc / 指令关掉输入框：释放 Rust 侧闭包，避免玩家重开后旧闭包堆积。 */
    static void onClose(ServerPlayer player, AbstractContainerMenu menu) {
        if (menu instanceof NebulaAnvil anvil) {
            anvil.release();
        }
    }

    static final class NebulaAnvil extends AnvilMenu {
        private final ServerPlayer player;
        private final long guiId;
        private String text;
        private boolean done;

        NebulaAnvil(int id, Inventory inventory, ServerPlayer player, long guiId, String initial) {
            super(id, inventory, ContainerLevelAccess.NULL);
            this.player = player;
            this.guiId = guiId;
            this.text = initial == null ? "" : initial;
            ItemStack stack = new ItemStack(Items.PAPER);
            // 名字框的初始内容取自输入槽物品的名字；空串就是一个空输入框。
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(this.text));
            this.inputSlots.setItem(0, stack);
            this.slotsChanged(this.inputSlots);
        }

        /** 玩家每敲一个字都会进来一次，这里只记最新文本，不触发任何回调。 */
        @Override
        public boolean setItemName(String name) {
            boolean changed = super.setItemName(name);
            this.text = name == null ? "" : name;
            return changed;
        }

        @Override
        public void clicked(int slotId, int button, ClickType type, Player p) {
            if (slotId == 2) {
                confirm();
                return;
            }
            // 输入槽 / 材料槽只读：否则玩家能把占位纸拿走、把界面搞空。
            if (slotId >= 0 && slotId <= 1) {
                return;
            }
            super.clicked(slotId, button, type, p);
        }

        private void confirm() {
            if (done) {
                return;
            }
            done = true;
            JsonObject payload = NebulaMod.playerProfile(player);
            payload.addProperty("玩家名", player.getGameProfile().name());
            payload.addProperty("文本", text == null ? "" : text.trim());
            payload.addProperty("动作", "确认");
            player.closeContainer();
            try {
                JsonObject result = NebulaMod.parse(
                        NativeBridge.clickGui(NebulaMod.handle(), guiId, payload.toString()));
                NebulaMod.afterDispatch(player, result);
            } catch (Throwable t) {
                NebulaMod.log().warn("[星云] 输入框回调失败", t);
            }
        }

        void release() {
            if (done) {
                return;
            }
            done = true;
            try {
                NativeBridge.closeGui(NebulaMod.handle(), guiId);
            } catch (Throwable t) {
                NebulaMod.log().warn("[星云] 输入框释放失败", t);
            }
        }
    }
}
