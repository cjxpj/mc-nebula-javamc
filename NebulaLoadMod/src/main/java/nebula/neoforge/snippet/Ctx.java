package nebula.neoforge.snippet;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nebula.neoforge.McActions;
import nebula.neoforge.McApi;

/**
 * {@code $java.eval} 片段的上下文，也是片段能看见的全部世界。
 *
 * <p>两条互不干扰的通道：</p>
 * <ul>
 *   <li>{@link #query}/{@link #act}：直接复用 {@code 世界} 的 112 个方法（同名同参数），
 *       能力和词库完全一致，不会出现「词库干不了、Java 片段能干」的暗门；</li>
 *   <li>{@link #server}/{@link #player}/{@link #block} 等：拿真正的 Minecraft 对象，
 *       用于 {@code 世界} 没暴露、又必须当场调用的 API（应急补丁的主要用途）。</li>
 * </ul>
 *
 * <p>片段在服务端主线程同步执行；{@link #run} 走 Brigadier 的 parse+execute，
 * 语法错误会记日志而不是静默吞掉。</p>
 */
public final class Ctx {
    private static final Logger LOG = LogManager.getLogger("nebula");

    private final MinecraftServer server;
    private final String plugin;
    private final String source;
    /** 本次片段是否调用过 {@link #cancel()}，供片段内部判断用。 */
    private boolean cancelled;

    Ctx(MinecraftServer server, String plugin, String source) {
        this.server = server;
        this.plugin = plugin == null ? "" : plugin;
        this.source = source == null ? "" : source;
    }

    // ────────────────────────── 对象通道 ──────────────────────────

    /** 服务器实例；世界未就绪时为 null，片段要自己判空。 */
    public MinecraftServer server() {
        return server;
    }

    /** 所属插件名，日志与排查用。 */
    public String plugin() {
        return plugin;
    }

    /** 代码来源（文件路径或 {@code 内联}），出错时打日志用。 */
    public String source() {
        return source;
    }

    /** 维度 id 取世界，如 {@code minecraft:overworld}；不存在返回 null。 */
    public ServerLevel world(String dimension) {
        if (server == null || dimension == null || dimension.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(dimension.trim());
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    public ServerLevel overworld() {
        return world("minecraft:overworld");
    }

    /** 玩家名或 UUID；不在线返回 null。 */
    public ServerPlayer player(String nameOrUuid) {
        if (server == null || nameOrUuid == null || nameOrUuid.isBlank()) {
            return null;
        }
        String key = nameOrUuid.trim();
        ServerPlayer byName = server.getPlayerList().getPlayerByName(key);
        if (byName != null) {
            return byName;
        }
        try {
            return server.getPlayerList().getPlayer(java.util.UUID.fromString(key));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 按实体 id / UUID / 玩家名找实体；找不到返回 null。
     *
     * <p>数字 id 只在单个维度内唯一，所以会遍历全部维度，命中第一个就返回。</p>
     */
    public Entity entity(String raw) {
        if (server == null || raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        ServerPlayer player = player(key);
        if (player != null) {
            return player;
        }
        int id = -1;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            // 不是数字 id
        }
        java.util.UUID uuid = null;
        try {
            uuid = java.util.UUID.fromString(key);
        } catch (Exception ignored) {
            // 不是 UUID
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : allEntities(level)) {
                if (id >= 0 && entity.getId() == id) {
                    return entity;
                }
                if (uuid != null && uuid.equals(entity.getUUID())) {
                    return entity;
                }
            }
        }
        return null;
    }

    public List<ServerPlayer> players() {
        return server == null ? List.of() : new ArrayList<>(server.getPlayerList().getPlayers());
    }

    public List<Entity> entities(String dimension) {
        ServerLevel level = world(dimension);
        return level == null ? List.of() : allEntities(level);
    }

    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** 方块状态；维度不存在返回 null。 */
    public BlockState block(String dimension, int x, int y, int z) {
        ServerLevel level = world(dimension);
        return level == null ? null : level.getBlockState(new BlockPos(x, y, z));
    }

    /** 服务器 tick 计数（重启归零），做节流 / 冷却用。 */
    public int tick() {
        return server == null ? 0 : server.getTickCount();
    }

    /** 主世界游戏时间。 */
    public long gameTime() {
        ServerLevel level = overworld();
        return level == null ? 0L : level.getGameTime();
    }

    /** 是否在服务端主线程。片段由宿主在主线程调用，正常情况下恒为 true。 */
    public boolean onMainThread() {
        return server != null && server.isSameThread();
    }

    // ────────────────────────── 事件 ──────────────────────────

    /**
     * 取消当前事件，等价于词库里的 {@code $世界.取消事件$}。
     *
     * <p>写的是桥接层本次分发的取消标志，和 {@code $世界.取消事件$} 共用同一个开关。
     * 但**只有宿主真正消费这个标志的事件才会被取消**：目前是 {@code 玩家聊天} 与
     * {@code 方块破坏}。{@code 每秒} / {@code 实体受伤} / {@code 实体治疗} /
     * {@code 玩家进入} 调了只是一次空操作（{@code 实体受伤} 挂的是 Post 事件，伤害已扣完，
     * 事后取消在语义上不成立）。</p>
     *
     * @return 标志是否写回成功（桥接就绪才为 true）。<b>不代表事件一定被取消</b>，
     *         事件本身可不可取消只有宿主知道，调用方要自己保证用在对的事件上
     */
    public boolean cancel() {
        cancelled = true;
        try {
            nebula.neoforge.NativeBridge.markCancel();
            return true;
        } catch (Throwable t) {
            LOG.warn("[java/{}] 取消事件失败（桥接未就绪）：{}", plugin, t.toString());
            return false;
        }
    }

    /** 本次片段是否调用过 {@link #cancel()}。 */
    public boolean cancelled() {
        return cancelled;
    }

    // ────────────────────────── 词库同款通道 ──────────────────────────

    /**
     * 调一个 {@code 世界} 查询方法，返回宿主给的文本（多数是 JSON 串）。
     *
     * <pre>{@code
     * String json = ctx.query("取玩家坐标", "玩家", "Steve");
     * }</pre>
     */
    public String query(String op, Object... pairs) {
        return server == null ? "" : McApi.query(request(op, pairs).toString());
    }

    /**
     * 调一个 {@code 世界} 动作方法，立即在主线程落地。
     *
     * <pre>{@code
     * ctx.act("设方块", "维度", "minecraft:overworld", "x", 0, "y", 64, "z", 0, "方块", "minecraft:stone");
     * }</pre>
     */
    public void act(String op, Object... pairs) {
        if (server != null) {
            McActions.apply(server, request(op, pairs));
        }
    }

    /** 原版指令，不带前导斜杠。执行失败只记日志，不抛异常。 */
    public void run(String command) {
        if (server == null || command == null || command.isBlank()) {
            return;
        }
        try {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            ParseResults<CommandSourceStack> parsed =
                    server.getCommands().getDispatcher().parse(command, source);
            server.getCommands().getDispatcher().execute(parsed);
        } catch (CommandSyntaxException e) {
            LOG.warn("[java/" + plugin + "] 指令语法错误：" + command + " / " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("[java/" + plugin + "] 指令执行失败：" + command + " / " + e);
        }
    }

    // ────────────────────────── 输出 ──────────────────────────

    public void log(String message) {
        LOG.info("[java/" + plugin + "] " + message);
    }

    public void warn(String message) {
        LOG.warn("[java/" + plugin + "] " + message);
    }

    public void broadcast(String text) {
        if (server != null && text != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
        }
    }

    public void tell(String playerName, String text) {
        ServerPlayer player = player(playerName);
        if (player != null && text != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    // ────────────────────────── 内部 ──────────────────────────

    /** 拼 {@code {"op":…, 键:值,…}}；供 query/act 与片段自定义请求复用。 */
    public JsonObject request(String op, Object... pairs) {
        JsonObject object = new JsonObject();
        object.addProperty("op", op == null ? "" : op);
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                String key = String.valueOf(pairs[i]);
                Object value = pairs[i + 1];
                if (value instanceof JsonElement element) {
                    object.add(key, element);
                } else if (value instanceof Number number) {
                    object.addProperty(key, number);
                } else if (value instanceof Boolean flag) {
                    object.addProperty(key, flag);
                } else if (value instanceof Iterable<?> list) {
                    JsonArray array = new JsonArray();
                    for (Object item : list) {
                        array.add(String.valueOf(item));
                    }
                    object.add(key, array);
                } else {
                    object.addProperty(key, value == null ? "" : String.valueOf(value));
                }
            }
        }
        return object;
    }

    private static List<Entity> allEntities(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            out.add(entity);
        }
        return out;
    }
}
