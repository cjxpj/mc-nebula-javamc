package nebula.neoforge;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * 1.21.6+ 原版 dialog：带一个文本输入框的弹窗（MC 1.21.11 实测可用）。
 *
 * <p>1.21.1 没有服务端可下发的表单，登录插件只能靠聊天栏按钮 / 铁砧框。这里用
 * {@code Holder.direct()} 直接构造一个 {@link NoticeDialog} 下发给单个玩家，不需要往世界写
 * 数据包，也不需要 {@code /reload}。提交按钮挂 {@code dynamic/run_command}：客户端把输入框
 * 的值替换到 {@code $(pw)} 模板里，再把整条命令发给服务端执行——命令模板不带 {@code /} 前缀。</p>
 *
 * <p>注意 {@code canCloseWithEscape=false}，登录场景不希望玩家 Esc 直接跳过；密码是数字，
 * 模板替换会转义特殊字符，长度由 {@code maxLength} 与词库侧的双重校验兜住。</p>
 */
public final class DialogUi {
    private DialogUi() {
    }

    private static final String INPUT_KEY = "pw";

    /**
     * @param command 提交后要执行的指令名（如 {@code npw}），宿主补上 {@code " $(pw)"} 模板。
     */
    public static boolean showInput(ServerPlayer player, String title, String body,
                                    String label, int maxLength, String command) {
        if (player == null || command == null || command.isBlank()) {
            return false;
        }
        String raw = command.trim() + " $(" + INPUT_KEY + ")";
        ParsedTemplate parsed = ParsedTemplate.CODEC
                .parse(JsonOps.INSTANCE, new JsonPrimitive(raw))
                .result().orElse(null);
        if (parsed == null) {
            return false;
        }

        CommonDialogData common = new CommonDialogData(
                FloatingText.legacy(title),
                Optional.empty(),
                false,
                false,
                DialogAction.CLOSE,
                List.of(new PlainMessage(FloatingText.legacy(body), 200)),
                List.of(new Input(INPUT_KEY, new TextInput(200,
                        FloatingText.legacy(label), true, "",
                        Math.max(1, Math.min(256, maxLength)), Optional.empty()))));
        ActionButton submit = new ActionButton(
                new CommonButtonData(Component.literal("提交"), 150),
                Optional.of(new CommandTemplate(parsed)));
        Dialog dialog = new NoticeDialog(common, submit);
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
        return true;
    }
}
