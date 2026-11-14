package nebula.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@code 世界} 动作在宿主的落地层。
 *
 * <p>桥接层把每个动作方法序列化成带 {@code op} 的 JSON；这里按 op 执行。绝大多数动作用
 * 原版命令实现（自带权限、掉落、同步封包），必须读 Minecraft 对象的才直接调 API。
 * 完整方法清单见 {@code mod/docs/mc-api-map.md}。</p>
 */
public final class McActions {
    private static final Logger LOG = LogManager.getLogger("nebula");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private McActions() {
    }

    /** 由 {@link NebulaMod#executeActions} 的 default 分支调用。 */
    public static void apply(MinecraftServer server, JsonObject action) {
        if (server == null || action == null) {
            return;
        }
        String op = McSupport.str(action, "op");
        try {
            switch (op) {
                // ── 1. 方块 ──
                case "设方块" -> run(server, in(action) + "setblock " + xyz(action, "x", "y", "z")
                        + " " + clean(McSupport.str(action, "方块")));
                case "设方块状态" -> {
                    String name = clean(McSupport.str(action, "名称"));
                    String state = clean(McSupport.str(action, "状态"));
                    if (!state.isEmpty()) {
                        name += state.startsWith("[") ? state : "[" + state + "]";
                    }
                    run(server, in(action) + "setblock " + xyz(action, "x", "y", "z") + " " + name);
                }
                case "设方块数据" -> run(server, in(action) + "setblock "
                        + xyz(action, "x", "y", "z") + " " + clean(McSupport.str(action, "数据")));
                case "设生物群系" -> run(server, in(action) + "fillbiome "
                        + McSupport.integer(action, "x1", 0) + " -64 " + McSupport.integer(action, "z1", 0) + " "
                        + McSupport.integer(action, "x2", 0) + " 319 " + McSupport.integer(action, "z2", 0) + " "
                        + clean(McSupport.str(action, "群系")));
                case "填充区域" -> run(server, in(action) + "fill "
                        + xyz(action, "x1", "y1", "z1") + " " + xyz(action, "x2", "y2", "z2") + " "
                        + clean(McSupport.str(action, "方块")));
                // ── 2. 坐标 ──
                case "设复活点" -> run(server, "setworldspawn " + xyz(action, "x", "y", "z"));
                // ── 3. 玩家 ──
                case "传送玩家" -> run(server, in(action) + "tp " + playerArg(server, action) + " "
                        + xyz(action, "x", "y", "z"));
                case "设置模式" -> run(server, "gamemode " + mode(McSupport.str(action, "模式")) + " "
                        + playerArg(server, action));
                case "治疗玩家" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.heal((float) Math.max(0.0, McSupport.number(action, "数值", 1.0)));
                    }
                }
                case "伤害玩家" -> run(server, "damage " + playerArg(server, action) + " "
                        + Math.max(0.0, McSupport.number(action, "数值", 1.0)) + " minecraft:generic");
                case "击杀玩家" -> run(server, "kill " + playerArg(server, action));
                case "踢出玩家" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.connection.disconnect(
                                Component.literal(McSupport.str(action, "原因")));
                    }
                }
                case "设置侧边栏" -> sidebar(server, action);
                case "清除侧边栏" -> run(server, "scoreboard objectives setdisplay sidebar");
                case "设置等级" -> run(server, "experience set " + playerArg(server, action) + " "
                        + Math.max(0, McSupport.integer(action, "数值", 0)) + " levels");
                case "加等级" -> run(server, "experience add " + playerArg(server, action) + " "
                        + McSupport.integer(action, "数值", 1) + " levels");
                case "加经验" -> run(server, "experience add " + playerArg(server, action) + " "
                        + McSupport.integer(action, "数值", 1) + " points");
                case "设置经验进度" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        float value = (float) Math.max(0.0, Math.min(1.0,
                                McSupport.number(action, "数值", 0.0)));
                        McSupport.setField(player, "experienceProgress", value);
                    }
                }
                case "设置饥饿", "设置饱和度", "设置消耗度" -> food(server, action, op);
                case "设置权限" -> {
                    boolean op4 = McSupport.integer(action, "等级", 0) > 0;
                    run(server, (op4 ? "op " : "deop ") + playerName(server, action));
                }
                case "给予物品" -> run(server, "give " + playerArg(server, action) + " "
                        + clean(McSupport.str(action, "物品")) + " "
                        + Math.max(1, McSupport.integer(action, "数量", 1)));
                case "丢弃物品" -> dropItem(server, action);
                case "设背包物品" -> setSlot(server, action);
                case "清空背包" -> clearInventory(server, action);
                case "设置副手" -> setOffhand(server, action);
                case "加效果" -> {
                    int seconds = Math.max(1, McSupport.integer(action, "秒", 30));
                    int level = Math.max(0, McSupport.integer(action, "等级", 1) - 1);
                    run(server, "effect give " + playerArg(server, action) + " "
                            + clean(McSupport.str(action, "效果")) + " " + seconds + " " + level);
                }
                case "移除效果" -> run(server, "effect clear " + playerArg(server, action) + " "
                        + clean(McSupport.str(action, "效果")));
                case "设置能力" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        ability(player, McSupport.str(action, "能力"),
                                McSupport.bool(action, "值", true));
                    }
                }
                case "设置飞行" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        ability(player, "飞行", McSupport.bool(action, "值", true));
                    }
                }
                case "设置无敌" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        ability(player, "无敌", McSupport.bool(action, "值", true));
                    }
                }
                case "设置隐身" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.setInvisible(McSupport.bool(action, "值", true));
                    }
                }
                case "设置名牌" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.setCustomName(Component.literal(McSupport.str(action, "文本")));
                    }
                }
                case "设置名牌可见" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.setCustomNameVisible(McSupport.bool(action, "值", true));
                    }
                }
                case "玩家加标签", "玩家移除标签" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    String tag = McSupport.str(action, "标签").trim();
                    if (player != null && !tag.isEmpty()) {
                        if (op.endsWith("移除标签")) {
                            player.removeTag(tag);
                        } else {
                            player.addTag(tag);
                        }
                    }
                }
                case "设置玩家出生点" -> run(server, in(action) + "spawnpoint "
                        + playerArg(server, action) + " " + xyz(action, "x", "y", "z"));
                case "设置区块半径" -> setChunkRadius(server, action);
                case "重发区块" -> resendChunks(server, action);
                case "打开背包" -> unsupported(op,
                        "玩家背包是客户端本地 GUI，服务端不接管；用 创建界面 + 打开界面 打开箱子界面");
                case "播放动作" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        animate(player, McSupport.str(action, "动作"));
                    }
                }
                case "骑乘" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    Entity target = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (player != null && target != null) {
                        player.startRiding(target);
                    }
                }
                case "取消骑乘" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    if (player != null) {
                        player.stopRiding();
                    }
                }
                case "攻击" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    Entity target = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (player != null && target != null) {
                        player.attack(target);
                    }
                }
                case "交互" -> {
                    ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
                    Entity target = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (player != null && target != null) {
                        interact(player, target);
                    }
                }
                // ── 4. 世界 / 服务器 ──
                case "设时间" -> run(server, "time set " + McSupport.integer(action, "时刻", 0));
                case "设天气" -> run(server, "weather " + weather(McSupport.str(action, "天气")));
                case "更新天气" -> run(server, "weather rain " + Math.max(1,
                        McSupport.integer(action, "数值", 300)));
                case "设难度" -> run(server, "difficulty " + difficulty(McSupport.str(action, "难度")));
                case "设游戏规则" -> run(server, "gamerule " + clean(McSupport.str(action, "名称"))
                        + " " + clean(McSupport.str(action, "值")));
                case "保存世界" -> run(server, "save-all");
                case "设环境变量" -> McSupport.setEnv(McSupport.str(action, "名称"),
                        McSupport.str(action, "值"));
                // 1.21.1 自带 /tick（1.20.3 起），不需要 Mixin 改 tick 循环。
                case "设置tick冻结" -> run(server,
                        McSupport.bool(action, "值", true) ? "tick freeze" : "tick unfreeze");
                case "步进tick" -> run(server, "tick step "
                        + Math.max(1, McSupport.integer(action, "数值", 1)) + "t");
                case "设置tick加速" -> {
                    double raw = McSupport.number(action, "数值", 0.0);
                    float rate = raw > 0.0 ? (float) raw : 20.0F;
                    run(server, "tick rate " + Math.max(1.0F, Math.min(10000.0F, rate)));
                }
                // ── 5. 实体 ──
                case "生成生物" -> run(server, in(action) + "summon "
                        + clean(McSupport.str(action, "类型")) + " " + xyz(action, "x", "y", "z"));
                case "生成实体" -> {
                    String nbt = McSupport.str(action, "数据").trim();
                    run(server, in(action) + "summon " + clean(McSupport.str(action, "类型")) + " "
                            + xyz(action, "x", "y", "z") + (nbt.isEmpty() ? "" : " " + nbt));
                }
                case "生成粒子" -> run(server, in(action) + "particle "
                        + clean(McSupport.str(action, "效果")) + " " + xyz(action, "x", "y", "z"));
                case "实体传送" -> run(server, in(action) + "tp " + entityArg(server, action, "实体")
                        + " " + xyz(action, "x", "y", "z"));
                case "实体伤害" -> run(server, "damage " + entityArg(server, action, "实体") + " "
                        + Math.max(0.0, McSupport.number(action, "数值", 1.0)) + " minecraft:generic");
                case "实体治疗" -> {
                    Entity entity = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (entity instanceof LivingEntity living) {
                        living.heal((float) Math.max(0.0, McSupport.number(action, "数值", 1.0)));
                    }
                }
                case "实体击杀" -> run(server, "kill " + entityArg(server, action, "实体"));
                case "实体移除" -> {
                    Entity entity = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (entity != null) {
                        entity.discard();
                    }
                }
                case "实体加标签", "实体移除标签" -> {
                    Entity entity = McSupport.entity(server, McSupport.str(action, "实体"));
                    String tag = McSupport.str(action, "标签").trim();
                    if (entity != null && !tag.isEmpty()) {
                        if (op.endsWith("移除标签")) {
                            entity.removeTag(tag);
                        } else {
                            entity.addTag(tag);
                        }
                    }
                }
                case "实体隐身" -> {
                    Entity entity = McSupport.entity(server, McSupport.str(action, "实体"));
                    if (entity != null) {
                        entity.setInvisible(McSupport.bool(action, "值", true));
                    }
                }
                default -> LOG.warn("[星云] 未知 世界 动作：{}", op);
            }
        } catch (Exception e) {
            LOG.warn("[星云] 世界.{} 执行失败：{}", op, e.toString());
        }
    }

    // ────────────────────────── 通用工具 ──────────────────────────

    private static void run(MinecraftServer server, String command) {
        if (server == null || command == null || command.isBlank()) {
            return;
        }
        try {
            // performPrefixedCommand 在 1.21.1 返回 void 并把语法错误吞进 sendFailure；
            // withSuppressedOutput 又把回显压掉，失败就完全静默了。直接走 Brigadier
            // 的 parse + execute，异常和返回 0 都能看见。
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            ParseResults<CommandSourceStack> parsed =
                    server.getCommands().getDispatcher().parse(command, source);
            // 注意：返回 0 不一定是失败——gamemode 模式没变、scoreboard remove 目标不存在
            // 都会返回 0。所以只把真正的 CommandSyntaxException 记成错误。
            server.getCommands().getDispatcher().execute(parsed);
        } catch (CommandSyntaxException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!harmless(message)) {
                LOG.warn("[星云] 世界 命令语法错误：{} / {}", command, message);
            }
        } catch (Exception e) {
            LOG.warn("[星云] 世界 命令执行失败：{} / {}", command, e.toString());
        }
    }

    /**
     * 原版命令把「状态没变 / 没人看得见 / 本来就没有该状态」也做成异常，这些不是 世界 动作的故障。
     * 真失败（目标不存在、区块未加载、UUID 被拒绝）仍会打 WARN。
     */
    private static boolean harmless(String message) {
        return message.contains("Nothing changed")
                || message.contains("did not change")
                || message.contains("was not visible for anybody")
                || message.contains("Unknown scoreboard objective")
                // effect clear <目标> <药效>：目标身上没有该药效（commands.effect.clear.specific.failed）
                || message.contains("doesn't have the requested effect")
                // effect clear <目标>（不带药效）：目标身上没有任何药效（commands.effect.clear.everything.failed）
                || message.contains("has no effects to remove");
    }

    /** 命令拼接只允许单行、不带引号的文本，避免词库变量把命令拆成两段。 */
    private static String clean(String raw) {
        return raw == null ? "" : raw.replace("\r", " ").replace("\n", " ")
                .replace("\"", "").trim();
    }

    /**
     * 命令里的玩家目标必须用 GameProfile 名字。
     *
     * <p>1.21.1 实测：{@code give <uuid>} / {@code experience set <uuid>} 这类「只允许玩家」
     * 的命令会把 UUID 字面量当成实体选择器并直接拒绝；{@code tp <uuid>} 因为接受实体反而
     * 能用。统一用名字最稳，且不要取 {@code getName()}（会被 {@code 设置名牌} 改成显示名）。</p>
     */
    private static String playerArg(MinecraftServer server, JsonObject action) {
        String raw = McSupport.str(action, "玩家");
        ServerPlayer player = McSupport.player(server, raw);
        return player != null ? player.getGameProfile().name() : clean(raw);
    }

    private static String playerName(MinecraftServer server, JsonObject action) {
        String raw = McSupport.str(action, "玩家");
        ServerPlayer player = McSupport.player(server, raw);
        return player != null ? player.getGameProfile().name() : clean(raw);
    }

    private static String entityArg(MinecraftServer server, JsonObject action, String key) {
        String selector = McSupport.selector(server, McSupport.str(action, key));
        return selector == null ? "" : selector;
    }

    private static String in(JsonObject action) {
        String dim = clean(McSupport.str(action, "维度"));
        return "execute in " + (dim.isEmpty() ? "minecraft:overworld" : dim) + " run ";
    }

    private static String xyz(JsonObject action, String... keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(McSupport.integer(action, key, 0));
        }
        return out.toString();
    }

    private static String mode(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "创造", "creative", "1", "c" -> "creative";
            case "冒险", "adventure", "2", "a" -> "adventure";
            case "旁观", "spectator", "3", "sp" -> "spectator";
            default -> "survival";
        };
    }

    private static String weather(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "雨", "下雨", "rain" -> "rain";
            case "雷", "雷雨", "thunder" -> "thunder";
            default -> "clear";
        };
    }

    private static String difficulty(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "和平", "peaceful", "0" -> "peaceful";
            case "简单", "easy", "1" -> "easy";
            case "困难", "hard", "3" -> "hard";
            default -> "normal";
        };
    }

    /** 侧边栏目标只建一次；先查 Scoreboard，避免发注定会失败的 remove / add。 */
    private static void sidebar(MinecraftServer server, JsonObject action) {
        String title = clean(McSupport.str(action, "文本")).replace(" ", "_");
        String display = title.isEmpty() ? "星云" : title;
        if (server.getScoreboard().getObjective("nebula_sidebar") == null) {
            run(server, "scoreboard objectives add nebula_sidebar dummy \"" + display + "\"");
        } else {
            run(server, "scoreboard objectives modify nebula_sidebar displayname \"" + display + "\"");
        }
        run(server, "scoreboard objectives setdisplay sidebar nebula_sidebar");
    }

    private static void unsupported(String op, String reason) {
        if (WARNED.add(op)) {
            LOG.warn("[星云] 世界.{} 未启用：{}", op, reason);
        }
    }

    // ────────────────────────── 背包 / 能力 ──────────────────────────

    private static void food(MinecraftServer server, JsonObject action, String op) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        Object data = McSupport.invoke(player, "getFoodData");
        switch (op) {
            case "设置饥饿" -> McSupport.invokeLoose(data, "setFoodLevel",
                    Math.max(0, McSupport.integer(action, "数值", 20)));
            case "设置饱和度" -> McSupport.invokeLoose(data, "setSaturation",
                    (float) Math.max(0.0, McSupport.number(action, "数值", 5.0)));
            default -> McSupport.invokeLoose(data, "setExhaustion",
                    (float) Math.max(0.0, McSupport.number(action, "数值", 0.0)));
        }
    }

    private static void setSlot(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        Item item = McSupport.item(McSupport.str(action, "物品"));
        int slot = McSupport.integer(action, "槽位", 0);
        if (item == Items.AIR || slot < 0 || slot >= player.getInventory().getNonEquipmentItems().size()) {
            return;
        }
        player.getInventory().setItem(slot,
                new ItemStack(item, Math.max(1, McSupport.integer(action, "数量", 1))));
        player.inventoryMenu.broadcastChanges();
    }

    private static void setOffhand(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null || player.getInventory().getItem(Inventory.SLOT_OFFHAND).isEmpty()) {
            return;
        }
        Item item = McSupport.item(McSupport.str(action, "物品"));
        if (item == Items.AIR) {
            return;
        }
        player.getInventory().setItem(Inventory.SLOT_OFFHAND,
                new ItemStack(item, Math.max(1, McSupport.integer(action, "数量", 1))));
        player.inventoryMenu.broadcastChanges();
    }

    private static void clearInventory(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        clearList(McSupport.inventorySlots(inventory));
        player.inventoryMenu.broadcastChanges();
    }

    private static void clearList(List<ItemStack> stacks) {
        for (int i = 0; i < stacks.size(); i++) {
            stacks.set(i, ItemStack.EMPTY);
        }
    }

    /** 从背包扣掉若干物品，再把扣到的数量丢在玩家脚下。 */
    private static void dropItem(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        Item item = McSupport.item(McSupport.str(action, "物品"));
        if (item == Items.AIR) {
            return;
        }
        int wanted = Math.max(1, McSupport.integer(action, "数量", 1));
        Inventory inventory = player.getInventory();
        int remaining = takeFrom(McSupport.inventorySlots(inventory), item, wanted);
        int dropped = wanted - remaining;
        if (dropped > 0) {
            int left = dropped;
            int max = Math.max(1, new ItemStack(item).getMaxStackSize());
            while (left > 0) {
                int count = Math.min(left, max);
                player.drop(new ItemStack(item, count), false);
                left -= count;
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static int takeFrom(List<ItemStack> stacks, Item item, int remaining) {
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                stacks.set(i, ItemStack.EMPTY);
            }
            remaining -= take;
        }
        return remaining;
    }

    private static void ability(ServerPlayer player, String raw, boolean value) {
        Object abilities = McSupport.invoke(player, "getAbilities");
        if (abilities == null) {
            return;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "飞行", "fly", "flying" -> {
                McSupport.setField(abilities, "mayfly", value);
                McSupport.setField(abilities, "flying", value && Boolean.TRUE.equals(
                        McSupport.field(abilities, "mayfly")));
            }
            case "允许飞行", "mayfly" -> McSupport.setField(abilities, "mayfly", value);
            case "无敌", "invulnerable" -> McSupport.setField(abilities, "invulnerable", value);
            case "建造", "maybuild" -> McSupport.setField(abilities, "mayBuild", value);
            case "瞬移", "instabuild" -> McSupport.setField(abilities, "instabuild", value);
            default -> McSupport.setField(abilities, key, value);
        }
        McSupport.invoke(player, "onUpdateAbilities");
    }

    private static void animate(ServerPlayer player, String raw) {
        int id = 0;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "受伤", "hurt" -> id = 1;
            case "醒来", "wake" -> id = 2;
            case "暴击", "crit" -> id = 4;
            case "魔法暴击", "magiccrit" -> id = 5;
            default -> {
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException ignored) {
                    id = 0;
                }
            }
        }
        player.connection.send(new ClientboundAnimatePacket(player, id));
    }

    /** 客户端区块缓存半径；1.21.1 有原生封包，不需要 Mixin。 */
    private static void setChunkRadius(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        int radius = McSupport.integer(action, "数值", 8);
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(
                Math.max(2, Math.min(32, radius))));
    }

    /** 重发玩家附近已加载的区块；半径默认 0（只重发所在区块），上限 2。 */
    private static void resendChunks(MinecraftServer server, JsonObject action) {
        ServerPlayer player = McSupport.player(server, McSupport.str(action, "玩家"));
        if (player == null) {
            return;
        }
        ServerLevel level = player.level();
        int radius = Math.max(0, Math.min(2, McSupport.integer(action, "数值", 0)));
        var center = player.chunkPosition();
        int sent = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkAccess chunk = level.getChunk(
                        center.x + dx, center.z + dz, ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk full) {
                    // 原版 ChunkMap 也是传 null 的 BitSet，表示发送完整光照数据。
                    player.connection.send(new ClientboundLevelChunkWithLightPacket(
                            full, level.getLightEngine(), null, null));
                    sent++;
                }
            }
        }
        if (sent == 0) {
            LOG.warn("[星云] 世界.重发区块 没有可重发的已加载区块");
        }
    }

    /** interactOn 的签名随版本浮动，按参数个数反射调用。 */
    private static void interact(ServerPlayer player, Entity target) {
        try {
            Class<?> handClass = Class.forName("net.minecraft.world.InteractionHand");
            Object mainHand = null;
            for (Object constant : handClass.getEnumConstants()) {
                if ("MAIN_HAND".equals(((Enum<?>) constant).name())) {
                    mainHand = constant;
                }
            }
            McSupport.invokeLoose(player, "interactOn", target, mainHand);
        } catch (Exception ignored) {
            // 该版本没有可调用的交互方法
        }
    }
}
