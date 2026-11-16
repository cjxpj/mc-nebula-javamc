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
import net.minecraft.server.dialog.MultiActionDialog;
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
 * <p>1.21.6+ 起服务端可直接下发 dialog 表单；这里用
 * {@code Holder.direct()} 下发给单个玩家，不需要往世界写数据包，也不需要 {@code /reload}。
 * 提交按钮挂 {@code dynamic/run_command}：客户端把输入框的值替换到 {@code $(pw)} 模板里，
 * 再把整条命令发给服务端执行——命令模板不带 {@code /} 前缀。</p>
 *
 * <p>{@code exitCommand} 为空时用单按钮 {@link NoticeDialog}；非空时改用
 * {@link MultiActionDialog}，左侧放「退出服务器」（执行该指令断开连接）、右侧放「提交」，
 * 给未登录玩家一个明确的出口。列表顺序即从左到右，{@code columns=2} 让两按钮排一行。</p>
 *
 * <p><b>坑</b>：原版 {@link ParsedTemplate} 的 CODEC 只接受「至少含一个 {@code $(变量)}
 * 的宏模板」，纯文本如 {@code nquit} 会报 {@code No variables in macro} 并解析失败。
 * 早先版本直接把退出指令丢进去，失败后整个弹窗一起 {@code return false} —— 表现就是
 * 「玩家进服完全没有弹窗」。现在退出指令缺变量时自动补 {@code $(pw)} 占位，解析仍失败
 * 也只退回单按钮，绝不让附加按钮拖垮弹窗本身。</p>
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
     * @param exitCommand 左侧「退出服务器」按钮要执行的指令名；空串表示不加这个按钮。
     */
    public static boolean showInput(ServerPlayer player, String title, String body,
                                    String label, int maxLength, String command,
                                    String exitCommand) {
        if (player == null || command == null || command.isBlank()) {
            return false;
        }
        // 哨兵前缀「#」：模板里的 $(pw) 在输入框为空时会被实例化成空串，整条命令就成了 "npw "
        // —— literal 后面只剩一个空格、没有任何参数内容，Brigadier 会判
        // 「Incorrect argument for command」（玩家看到「错误的命令参数 / npw <--[此处]」，
        // 很像「这条指令不存在」，其实是参数形态不合法）。固定前缀让空输入也得到 "npw #"
        // 这种合法 token，词库侧把前缀剥掉即可。
        String raw = command.trim() + " #$(" + INPUT_KEY + ")";
        ParsedTemplate parsed = ParsedTemplate.CODEC
                .parse(JsonOps.INSTANCE, new JsonPrimitive(raw))
                .result().orElse(null);
        if (parsed == null) {
            return false;
        }

        CommonDialogData common = new CommonDialogData(
                FloatingText.legacy(title),
                Optional.empty(),
                // 必须允许 ESC：按钮走的是 run_command，客户端本地解析不出这条指令时会弹
                // 「确认仍要发送」而不是直接发包；万一点不掉，禁 ESC 就等于把玩家锁死在弹窗里
                // （冻结 + 失明还在，ESC 只是关面板，逃不掉验证流程）。
                true,
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
        if (exitCommand != null && !exitCommand.isBlank()) {
            // 原版 ParsedTemplate 要求模板里至少有一个 $(变量)，否则 CODEC 直接
            // 报 "No variables in macro" 解析失败。退出按钮本身不需要输入值，
            // 这里补一个 $(pw) 占位；宿主指令自己忽略这个多余参数即可。
            String exitRaw = exitCommand.trim();
            if (!exitRaw.contains("$(")) {
                // 同提交按钮：补 $(pw) 占位，并带同一个「#」哨兵前缀（空输入时命令合法）。
                exitRaw = exitRaw + " #$(" + INPUT_KEY + ")";
            }
            ParsedTemplate exitParsed = ParsedTemplate.CODEC
                    .parse(JsonOps.INSTANCE, new JsonPrimitive(exitRaw))
                    .result().orElse(null);
            if (exitParsed != null) {
                ActionButton exitButton = new ActionButton(
                        new CommonButtonData(FloatingText.legacy("§c退出服务器"), 150),
                        Optional.of(new CommandTemplate(exitParsed)));
                dialog = new MultiActionDialog(common, List.of(exitButton, submit),
                        Optional.empty(), 2);
            }
            // 退出按钮拿不到模板时退回单按钮弹窗：弹窗不能因为附加按钮而整个消失。
        }
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
        return true;
    }
}
