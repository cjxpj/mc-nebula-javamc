package nebula.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * 可点击聊天行。
 *
 * <p>词库传一行「段」的 JSON 数组：{@code [{"文":"[1]","命令":"/登录键 1","悬停":"输入 1"}, …]}，
 * 宿主把每段编成带 {@code ClickEvent.RunCommand} 的组件拼成一条聊天消息发出去。原版客户端
 * 点这段文字就等于以玩家身份执行那条命令，所以点击按钮不需要任何客户端 mod。</p>
 *
 * <p>没有 {@code 命令} 的段就是普通文本（用来拼说明文字）。</p>
 */
public final class ClickChat {
    private ClickChat() {
    }

    static void send(ServerPlayer player, String raw) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (Exception e) {
            NebulaMod.log().warn("[星云] 点击消息 JSON 解析失败：{}", raw);
            return;
        }
        if (!parsed.isJsonArray()) {
            NebulaMod.log().warn("[星云] 点击消息需要 JSON 数组：{}", raw);
            return;
        }
        JsonArray array = parsed.getAsJsonArray();
        MutableComponent line = Component.empty();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject seg = element.getAsJsonObject();
            String text = seg.has("文") ? seg.get("文").getAsString() : "";
            String command = seg.has("命令") ? seg.get("命令").getAsString() : "";
            String hover = seg.has("悬停") ? seg.get("悬停").getAsString() : "";
            MutableComponent part = Component.literal(text);
            if (!command.isEmpty()) {
                part.withStyle(style -> style.withClickEvent(
                        new ClickEvent.RunCommand(command)));
                if (!hover.isEmpty()) {
                    part.withStyle(style -> style.withHoverEvent(
                            new HoverEvent.ShowText(Component.literal(hover))));
                }
            }
            line.append(part);
        }
        player.sendSystemMessage(line);
    }
}
