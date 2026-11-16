package nebula.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import nebula.neoforge.content.Containers;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Rust 词库 {@code 世界} 查询回调。
 *
 * <p>所有查询都在服务端主线程同步执行：Rust 在事件分发中调用 {@link #query(String)}，
 * Java 读世界后把结果按文本 / JSON 文本返回，词库据此继续计算。动作类走
 * {@link NativeBridge#dispatch} 返回的 actions 队列，由 {@link McActions} 落地。</p>
 *
 * <p>完整方法清单与参数约定见 {@code NebulaLoadMod/docs/mc-api-map.md}。</p>
 */
public final class McApi {
    private McApi() {
    }

    /** 物品 id → Item；界面与动作落地沿用这个入口。 */
    static Item item(String id) {
        return McSupport.item(id);
    }

    /** 查询入口；无法识别的操作或世界未就绪时返回空串。 */
    public static String query(String requestJson) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return "";
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(requestJson).getAsJsonObject();
        } catch (Exception e) {
            return "";
        }
        String op = McSupport.str(request, "op");
        return switch (op) {
            // 方块
            case "取相邻方块" -> neighbors(server, request);
            case "取方块" -> block(server, request, 0);
            case "取方块数据" -> block(server, request, 1);
            case "获取方块" -> block(server, request, 2);
            case "取方块标签" -> block(server, request, 3);
            case "取生物群系" -> block(server, request, 4);
            case "区块已加载" -> chunkLoaded(server, request);
            // 坐标
            case "取玩家坐标" -> playerPosition(server, request);
            case "取复活点坐标" -> spawnPosition(server);
            // 玩家
            case "取玩家状态" -> playerStatus(server, request);
            case "取玩家列表" -> playerList(server);
            case "取玩家数量" -> String.valueOf(server.getPlayerList().getPlayers().size());
            case "取玩家模式" -> modeLabel(McSupport.player(server, McSupport.str(request, "玩家")));
            case "取玩家血量" -> health(McSupport.player(server, McSupport.str(request, "玩家")));
            case "取玩家权限" -> permission(server, McSupport.player(server, McSupport.str(request, "玩家")));
            case "取手持物品" -> heldItem(server, request);
            case "取背包物品" -> inventoryItem(server, request);
            case "取副手物品" -> offhandItem(server, request);
            case "取装备" -> armor(server, request);
            case "玩家有标签" -> entityTag(server, McSupport.str(request, "玩家"),
                    McSupport.str(request, "标签"));
            case "取物品数" -> countItem(server, request);
            // 皮肤状态：词库用 ${世界.取皮肤 玩家} 显示「当前用的是哪个来源」。
            case "取皮肤" -> SkinService.describe(server, McSupport.str(request, "玩家"));
            // 世界 / 服务器
            case "取时间" -> dayTime(server);
            case "取难度" -> difficulty(server);
            case "取种子" -> seed(server);
            case "取游戏规则" -> gameRule(server, McSupport.str(request, "名称"));
            case "取睡眠状态" -> sleepState(server);
            case "取TPS" -> McSupport.tps();
            case "取MSPT" -> McSupport.mspt();
            case "取服务器tick" -> McSupport.tickCount();
            case "取协议版本" -> protocolVersion(server);
            case "取服务器版本" -> server.getServerVersion();
            // 列表 MOTD 的当前值（DedicatedServer 每次现读 settings，改了立刻可见）。
            case "取MOTD" -> {
                String motd = server.getMotd();
                yield motd == null ? "" : motd;
            }
            case "取环境变量" -> McSupport.env(McSupport.str(request, "名称"));
            case "取本地时间" -> McSupport.localTime();
            case "取模拟暂停" -> tickFrozen(server);
            // 实体
            case "取实体列表" -> entityList(server, request);
            // 生物对象 `$怪.列表$`：按品种过滤（槽位共用，`取实体列表 类型` 做不到）。
            case "取生物列表" -> nebula.neoforge.content.MobRegistry.entitiesJson(
                    server,
                    McSupport.str(request, "品种"),
                    McSupport.integer(request, "上限", 64));
            // 物品注册表：已注册的物品 id 数组，供插件自动列菜单。
            case "取物品列表" -> nebula.neoforge.content.ItemRegistry.namesText();
            case "取实体坐标" -> entityPosition(server, request);
            case "取实体数据" -> entityData(server, request);
            case "实体有标签" -> entityTag(server, McSupport.str(request, "实体"),
                    McSupport.str(request, "标签"));
            case "取实体属性" -> attribute(server, request);
            // 生物寻路 / 容器：原语，策略留给词库（追谁、何时收、闲时怎么走）
            // 默认巡逻：一行调用搞定「满了不动 / 挑够得着的 / 近了收 / 远了追 / 闲时溜达」。
            // 想要自定义策略的插件不用它，直接用下面四个原语自己拼。
            // 刷怪蛋：按原版落点生成（射线取点击方块 + 碰撞外移 + 顶面抬一格），并扣掉手里那颗
            case "刷怪蛋生成" -> spawnEgg(server, request);
            case "巡逻" -> patrol(server, request);
            case "寻路" -> navigate(server, request);
            case "可达" -> reachable(server, request);
            case "附近掉落物" -> nearbyDrops(server, request);
            case "收取掉落物" -> collectDrops(server, request);
            case "容器空位" -> containerRoom(server, request);
            // 词库 $java.eval：编译并执行一段 Java 片段（见 nebula.neoforge.snippet）。
            case "java.eval" -> nebula.neoforge.snippet.JavaSnippet.eval(request);
            default -> "";
        };
    }

    // ────────────────────────── 方块 ──────────────────────────

    private static ServerLevel overworld(MinecraftServer server) {
        return McSupport.overworld(server);
    }

    /** 1.21.11 原生 public getter，不再需要按名字反射。 */
    private static String dayTime(MinecraftServer server) {
        ServerLevel level = overworld(server);
        return level == null ? "" : McSupport.text(level.getDayTime());
    }

    private static BlockPos pos(JsonObject request) {
        return new BlockPos(
                McSupport.integer(request, "x", 0),
                McSupport.integer(request, "y", 64),
                McSupport.integer(request, "z", 0));
    }

    /** kind: 0 名称 / 1 数据文本 / 2 完整对象 / 3 标签 / 4 生物群系。 */
    private static String block(MinecraftServer server, JsonObject request, int kind) {
        ServerLevel level = McSupport.level(server, McSupport.str(request, "维度"));
        if (level == null) {
            return emptyFor(kind);
        }
        BlockPos pos = pos(request);
        if (!level.isLoaded(pos)) {
            return emptyFor(kind);
        }
        BlockState state = level.getBlockState(pos);
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String data = state.toString();
        String dimension = level.dimension().identifier().toString();
        return switch (kind) {
            case 0 -> name;
            case 1 -> data;
            case 2 -> {
                JsonObject json = new JsonObject();
                json.addProperty("名称", name);
                json.addProperty("数据", data);
                json.add("标签", tags(state));
                json.addProperty("生物群系", biome(level, pos));
                json.addProperty("维度", dimension);
                json.addProperty("x", pos.getX());
                json.addProperty("y", pos.getY());
                json.addProperty("z", pos.getZ());
                yield json.toString();
            }
            case 3 -> tags(state).toString();
            default -> biome(level, pos);
        };
    }

    private static String emptyFor(int kind) {
        return switch (kind) {
            case 2 -> "{}";
            case 3 -> "[]";
            default -> "";
        };
    }

    private static JsonArray tags(BlockState state) {
        JsonArray out = new JsonArray();
        Object found = McSupport.invoke(state, "getTags");
        if (found instanceof Stream<?> stream) {
            stream.forEach(tag -> out.add(McSupport.text(McSupport.invoke(tag, "location"))));
        }
        return out;
    }

    private static String biome(ServerLevel level, BlockPos pos) {
        Object holder = McSupport.invoke1(level, "getBiome", BlockPos.class, pos);
        Object key = McSupport.invoke(holder, "unwrapKey");
        if (key instanceof Optional<?> optional && optional.isPresent()) {
            return McSupport.text(McSupport.invoke(optional.get(), "location"));
        }
        return "";
    }

    private static String chunkLoaded(MinecraftServer server, JsonObject request) {
        ServerLevel level = McSupport.level(server, McSupport.str(request, "维度"));
        if (level == null) {
            return "否";
        }
        BlockPos pos = new BlockPos(McSupport.integer(request, "x", 0) * 16, 64,
                McSupport.integer(request, "z", 0) * 16);
        return level.isLoaded(pos) ? "是" : "否";
    }

    // ────────────────────────── 坐标 ──────────────────────────

    private static String playerPosition(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "{}";
        }
        JsonObject json = new JsonObject();
        json.addProperty("维度", player.level().dimension().identifier().toString());
        json.addProperty("x", player.getX());
        json.addProperty("y", player.getY());
        json.addProperty("z", player.getZ());
        return json.toString();
    }

    private static String spawnPosition(MinecraftServer server) {
        ServerLevel level = overworld(server);
        if (level == null) {
            return "{}";
        }
        BlockPos blockPos = null;
        // 1.21.11：世界出生点收进 LevelData.RespawnData（record），
        // getSharedSpawnPos / getXSpawn 这些旧 getter 已删除，照旧反射只会拿到 null。
        // 之前查询恒返回 {}，登录插件拿到的空坐标被当成 0 0 0 传过去，冻结位置全乱。
        Object respawn = McSupport.tryInvoke(level, "getRespawnData");
        Object pos = McSupport.invoke(respawn, "pos");
        if (!(pos instanceof BlockPos)) {
            pos = McSupport.invoke(McSupport.invoke(respawn, "globalPos"), "pos");
        }
        if (pos instanceof BlockPos respawnPos) {
            blockPos = respawnPos;
        }
        // 旧版本（1.21.1 及更早）兼容。
        if (blockPos == null) {
            Object legacy = McSupport.tryInvoke(level, "getSharedSpawnPos");
            if (legacy instanceof BlockPos shared) {
                blockPos = shared;
            }
        }
        if (blockPos == null) {
            Object data = McSupport.invoke(level, "getLevelData");
            Object x = McSupport.invoke(data, "getXSpawn");
            Object y = McSupport.invoke(data, "getYSpawn");
            Object z = McSupport.invoke(data, "getZSpawn");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                blockPos = new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
            }
        }
        if (blockPos == null) {
            return "{}";
        }
        JsonObject json = new JsonObject();
        json.addProperty("维度", level.dimension().identifier().toString());
        json.addProperty("x", blockPos.getX());
        json.addProperty("y", blockPos.getY());
        json.addProperty("z", blockPos.getZ());
        return json.toString();
    }

    // ────────────────────────── 玩家 ──────────────────────────

    private static String playerStatus(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "{}";
        }
        JsonObject json = new JsonObject();
        json.addProperty("名称", player.getName().getString());
        json.addProperty("uuid", player.getUUID().toString());
        json.addProperty("维度", player.level().dimension().identifier().toString());
        json.addProperty("x", player.getX());
        json.addProperty("y", player.getY());
        json.addProperty("z", player.getZ());
        json.addProperty("模式", modeLabel(player));
        json.addProperty("血量", player.getHealth());
        json.addProperty("最大血量", player.getMaxHealth());
        json.addProperty("等级", McSupport.text(McSupport.field(player, "experienceLevel")));
        json.addProperty("权限", permission(server, player));
        json.addProperty("潜行", player.isShiftKeyDown() ? "是" : "否");
        JsonArray tagArray = new JsonArray();
        player.getTags().forEach(tagArray::add);
        json.add("标签", tagArray);
        return json.toString();
    }

    private static String playerList(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject item = new JsonObject();
            item.addProperty("名称", player.getName().getString());
            item.addProperty("uuid", player.getUUID().toString());
            out.add(item);
        }
        return out.toString();
    }

    private static String modeLabel(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        return switch (player.gameMode.getGameModeForPlayer()) {
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
            default -> "生存";
        };
    }

    private static String health(ServerPlayer player) {
        return player == null ? "0" : String.valueOf(player.getHealth());
    }

    private static String permission(MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return "0";
        }
        return server.getPlayerList().isOp(new NameAndId(player.getGameProfile())) ? "4" : "0";
    }

    private static String heldItem(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        return player == null ? "{}" : McSupport.itemJson(player.getMainHandItem()).toString();
    }

    private static String inventoryItem(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "{}";
        }
        int slot = McSupport.integer(request, "槽位", 0);
        if (slot < 0 || slot >= player.getInventory().getNonEquipmentItems().size()) {
            return "{}";
        }
        return McSupport.itemJson(player.getInventory().getItem(slot)).toString();
    }

    private static String offhandItem(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null || player.getInventory().getItem(Inventory.SLOT_OFFHAND).isEmpty()) {
            return "{}";
        }
        return McSupport.itemJson(player.getInventory().getItem(Inventory.SLOT_OFFHAND)).toString();
    }

    private static String armor(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "[]";
        }
        JsonArray out = new JsonArray();
        for (int slot = Inventory.SLOT_OFFHAND - 4; slot < Inventory.SLOT_OFFHAND; slot++) {
            out.add(McSupport.itemJson(player.getInventory().getItem(slot)));
        }
        return out.toString();
    }

    private static String entityTag(MinecraftServer server, String target, String tag) {
        Entity entity = McSupport.entity(server, target);
        return entity != null && !tag.isEmpty() && entity.getTags().contains(tag) ? "是" : "否";
    }

    /** 玩家背包（含盔甲 / 副手）里某种物品的总数；找不到玩家或物品时返回 0。 */
    private static String countItem(MinecraftServer server, JsonObject request) {
        ServerPlayer player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "0";
        }
        Item item = McSupport.item(McSupport.str(request, "物品"));
        if (item == Items.AIR) {
            return "0";
        }
        int total = 0;
        total += count(McSupport.inventorySlots(player.getInventory()), item);
        return String.valueOf(total);
    }

    private static int count(Iterable<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ────────────────────────── 世界 / 服务器 ──────────────────────────

    private static String difficulty(MinecraftServer server) {
        Object value = McSupport.invoke(overworld(server), "getDifficulty");
        if (value instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        return McSupport.text(value);
    }

    private static String seed(MinecraftServer server) {
        ServerLevel level = overworld(server);
        return level == null ? "0" : McSupport.text(level.getSeed());
    }

    /** 规则按 {@code GameRules.Key#getId()} 匹配；静态常量字段名不随版本稳定，只作兜底。 */
    private static String gameRule(MinecraftServer server, String name) {
        Object rules = McSupport.invoke(server, "getGameRules");
        if (rules == null || name.isBlank()) {
            return "";
        }
        String wanted = McSupport.normalized(name);
        String withoutDo = wanted.startsWith("do") ? wanted.substring(2) : wanted;
        for (Field field : rules.getClass().getDeclaredFields()) {
            String fieldName = field.getName();
            if (!Modifier.isStatic(field.getModifiers()) || !fieldName.startsWith("RULE_")) {
                continue;
            }
            Object key;
            try {
                field.setAccessible(true);
                key = field.get(null);
            } catch (Exception ignored) {
                continue;
            }
            if (key == null) {
                continue;
            }
            // 规则 id 就是 /gamerule 用的名字（doFireTick / doDaylightCycle），比常量名可靠。
            Object id = McSupport.tryInvoke(key, "getId", "id", "getName");
            String keyName = McSupport.normalized(McSupport.text(id));
            if (keyName.isEmpty()) {
                keyName = McSupport.normalized(fieldName.substring(5));
            }
            if (!keyName.equals(wanted) && !keyName.equals(withoutDo)) {
                continue;
            }
            for (Method method : rules.getClass().getDeclaredMethods()) {
                if (!method.getName().equals("getRule") || method.getParameterCount() != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(rules, key);
                    Object inner = McSupport.tryInvoke(value, "getValue", "get");
                    return McSupport.text(inner == null ? value : inner);
                } catch (Exception ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private static String sleepState(MinecraftServer server) {
        Object sleeping = McSupport.tryInvoke(overworld(server), "isSleeping", "isCurrentlySleeping");
        return Boolean.TRUE.equals(sleeping) ? "是" : "否";
    }

    /** 1.21.11 原生 {@code ServerTickRateManager}，不需要 Mixin 就能读冻结状态。 */
    private static String tickFrozen(MinecraftServer server) {
        return server.tickRateManager().isFrozen() ? "是" : "否";
    }

    private static String protocolVersion(MinecraftServer server) {
        // 1.21.11 推荐入口：getCurrentVersion() 未废弃，常量 RELEASE_NETWORK_PROTOCOL_VERSION 已 @Deprecated。
        return String.valueOf(SharedConstants.getCurrentVersion().protocolVersion());
    }

    // ────────────────────────── 实体 ──────────────────────────

    private static String entityList(MinecraftServer server, JsonObject request) {
        String dimension = McSupport.str(request, "维度");
        int limit = McSupport.integer(request, "上限", 512);
        if (limit <= 0 || limit > 512) {
            limit = 512;
        }
        // 类型过滤（可选）：给词库按类型遍历用，别让它每秒拖 512 条全量实体去自己筛。
        String typeFilter = McSupport.str(request, "类型").trim();
        final int maxEntities = limit;
        JsonArray out = new JsonArray();
        Object levels;
        if (dimension.isBlank() || dimension.equals("*") || dimension.equalsIgnoreCase("all")) {
            levels = McSupport.invoke(server, "getAllLevels");
        } else {
            ServerLevel level = McSupport.level(server, dimension);
            levels = level == null ? java.util.List.of() : java.util.List.of(level);
        }
        if (!(levels instanceof Iterable<?> iterable)) {
            return "[]";
        }
        for (Object item : iterable) {
            if (!(item instanceof ServerLevel level)) {
                continue;
            }
            for (Object candidate : McSupport.allEntities(level)) {
                if (!(candidate instanceof Entity entity)) {
                    continue;
                }
                if (!typeFilter.isEmpty()) {
                    Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                    if (key == null || !key.toString().equals(typeFilter)) {
                        continue;
                    }
                }
                if (out.size() >= maxEntities) {
                    return out.toString();
                }
                out.add(McSupport.entityJson(entity));
            }
        }
        return out.toString();
    }

    // ────────────────────────── 寻路 / 拾取（词库驱动 AI） ──────────────────────────

    /** 贴到多近就算「能收」。 */
    private static final double PICKUP_RANGE = 1.6;

    /**
     * {@code 巡逻 实体 [半径] [追速] [闲速] [溜达]}：<b>一套完整可用的默认巡逻</b>，一次调用做完全部判断。
     *
     * <p>顺序：</p>
     * <ol>
     *   <li>容器满了 → 什么都不做（返回 {@code 满}）；</li>
     *   <li>挑最近<b>走得到</b>的掉落物（走不到的跳过，不盯着发呆）；</li>
     *   <li>1.6 格内 → 收进容器（返回 {@code 收取}），否则寻路追过去（返回 {@code 追}）；</li>
     *   <li>一颗都够不着 / 周围没东西 → {@code 1/溜达} 的概率在 ±3 格内随机挪一挪（{@code 溜达} / {@code 闲}）。</li>
     * </ol>
     *
     * <p>这是给插件的「开箱即用」默认值：{@code $世界.巡逻 %怪%$} 一行就有完整行为。
     * 想要自己的策略就别调它，改用四个原语（{@code 附近掉落物} / {@code 可达} /
     * {@code 寻路} / {@code 收取掉落物}）自己写——两者互不影响。</p>
     *
     * @return 空串。默认策略<b>刻意不返回值</b>：宿主会把事件 / 函数的返回值打进日志，
     *         每秒每只怪一条状态会把 latest.log 刷满；要状态就用下面四个原语自己拼。
     */
    /** 手持物品的 {@code custom_data.nebula_id} 是否等于给定标记。 */
    private static boolean hasMarker(ItemStack stack, String marker) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && marker.equals(data.copyTag().getStringOr("nebula_id", ""));
    }

    /**
     * {@code 刷怪蛋生成 玩家 类型 [标记]}：<b>按原版刷怪蛋的落点</b>生成一只生物，并扣掉手里那颗。
     *
     * <p>原版 {@code SpawnEggItem#useOn} 的算法（1.21.11 字节码逐条对过）：</p>
     * <ol>
     *   <li>从玩家眼睛往前射线（距离取 {@code blockInteractionRange()}，约 4.5 格）；没打到方块就什么都不做；</li>
     *   <li>点到的方块<b>没有碰撞</b>（空气 / 草 / 花）→ 就生成在这一格；</li>
     *   <li>有碰撞 → 生成在「点到的那一面」外侧那一格；点的是顶面时 Y 再抬一格；</li>
     *   <li>点的是液体 → 生成在液体那一格（不受「要有碰撞」影响）；</li>
     *   <li>最后走 {@code EntityType#spawn(…, SPAWN_ITEM_USE, requireEmpty, invertY)}，居中 / 朝向 / 实体数据交给引擎。</li>
     * </ol>
     *
     * <p>这就是「原版刷怪蛋」和 {@code summon ~ ~ ~} 的区别：后者永远生成在<b>玩家脚下</b>。
     * 词库只需要一行 {@code $世界.刷怪蛋生成 %玩家% 实体类型 标记$}，落点、扣物品、权限都对齐原版。</p>
     *
     * @param 标记 可选；给定时要求手持物品的 {@code custom_data.nebula_id} 等于它，否则返回 {@code 0}（防误触）
     * @return {@code 1} 生成成功（已扣 1 个）/ {@code 0} 没生成（不扣）
     */
    private static String spawnEgg(MinecraftServer server, JsonObject request) {
        Player player = McSupport.player(server, McSupport.str(request, "玩家"));
        if (player == null) {
            return "0";
        }
        String marker = McSupport.str(request, "标记");
        // 和原版一样主手 / 副手都认：先在主手找标记，找不到再看副手
        ItemStack hand = player.getMainHandItem();
        if (!marker.isBlank() && !hasMarker(hand, marker)) {
            hand = player.getOffhandItem();
            if (!hasMarker(hand, marker)) {
                return "0";
            }
        }
        if (hand.isEmpty()) {
            return "0";
        }
        // `类型` 两种写法都收：品种 id（`nebula:chest_creature`）或原版实体注册名。
        // 品种走槽位池，生成后由 DynamicMob 绑定定义（属性 / 容器 / 外观）。
        String rawType = McSupport.str(request, "类型");
        String species = nebula.neoforge.content.MobRegistry.speciesOf(rawType);
        EntityType<?> type = species != null
                ? nebula.neoforge.content.MobRegistry.entityType(species)
                : BuiltInRegistries.ENTITY_TYPE
                        .get(Identifier.parse(rawType))
                        .map(holder -> holder.value())
                        .orElse(null);
        if (type == null || !(player.level() instanceof ServerLevel level)) {
            return "0";
        }
        // 原版落点：眼睛往前射线（含液体，原版用 ClipContext.Fluid.SOURCE_ONLY）
        HitResult picked = player.pick(player.blockInteractionRange(), 1.0F, true);
        if (!(picked instanceof BlockHitResult hit)) {
            return "0";
        }
        BlockPos clicked = hit.getBlockPos();
        Direction face = hit.getDirection();
        if (!level.mayInteract(player, clicked)) {
            return "0"; // 领地 / 出生点保护：和服务端「不能用物品」保持一致
        }
        BlockState state = level.getBlockState(clicked);
        BlockPos target;
        boolean requireEmpty;
        boolean invertY;
        if (state.getBlock() instanceof LiquidBlock) {
            target = clicked;
            requireEmpty = false;
            invertY = false;
        } else if (state.getCollisionShape(level, clicked).isEmpty()) {
            target = clicked;
            requireEmpty = true;
            invertY = false;
        } else {
            target = clicked.relative(face);
            requireEmpty = true;
            invertY = face == Direction.UP;
        }
        Entity spawned = type.spawn(level, hand, player, target,
                EntitySpawnReason.SPAWN_ITEM_USE, requireEmpty, invertY);
        if (spawned == null) {
            return "0";
        }
        // 品种生物：生成后立刻绑定定义，属性 / 容器格数 / 持久化才对得上。
        if (spawned instanceof nebula.neoforge.content.DynamicMob mob && species != null) {
            mob.bind(species);
        }
        hand.consume(1, player);
        return "1";
    }

    private static String patrol(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (!(entity instanceof Container container)) {
            return ""; // 不是容器，没有「往里装」这回事
        }
        if (roomLeft(container) == 0) {
            return ""; // 满了就站着，别再空转寻路
        }
        double radius = positive(request, "半径", 12.0);
        double chaseSpeed = positive(request, "追速", 1.2);
        double idleSpeed = positive(request, "闲速", 0.6);
        int wanderOneIn = Math.max(1, (int) decimal(request, "溜达", 4.0));

        ItemEntity target = firstReachable(entity, radius);
        if (target == null) {
            wander(entity, idleSpeed, wanderOneIn);
            return "";
        }
        if (entity.distanceToSqr(target) <= PICKUP_RANGE * PICKUP_RANGE) {
            collectAround(entity, container, PICKUP_RANGE);
            return "";
        }
        if (entity instanceof PathfinderMob mob) {
            mob.getNavigation().moveTo(target.getX(), walkY(target.getY()), target.getZ(), chaseSpeed);
        }
        return "";
    }

    /**
     * {@code 寻路 实体 x y z [速度]}：给实体下一个寻路目标，返回 {@code 1} 表示有路、{@code 0} 表示走不到。
     *
     * <p>只下目标、不管后续：实体自己会沿路径走过去（{@code PathNavigation} 是引擎能力，
     * 原版没有「走到坐标」命令，所以这一步只能由 mod 提供）。要不要换目标，全在词库。</p>
     */
    private static String navigate(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (!(entity instanceof PathfinderMob mob)) {
            return "0";
        }
        boolean ok = mob.getNavigation().moveTo(
                decimal(request, "x", mob.getX()),
                walkY(decimal(request, "y", mob.getY())),
                decimal(request, "z", mob.getZ()),
                positive(request, "速度", 0.7));
        return ok ? "1" : "0";
    }

    /**
     * {@code 附近掉落物 实体 半径}：半径内**所有**可拾取的掉落物，按距离升序的 JSON 数组。
     *
     * <p>字段：{@code id/uuid/x/y/z/距离}。这里<b>不做任何取舍</b>——挑哪颗、跳过哪颗（比如走不到的）
     * 由词库自己用 {@code 可达} 判断，mod 只负责把世界如实报出来。
     * 唯一被过滤掉的是原版硬规则：已消失、拾取延迟中（{@code hasPickUpDelay}）、空栈。</p>
     */
    private static String nearbyDrops(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (entity == null) {
            return "[]";
        }
        JsonArray out = new JsonArray();
        for (ItemEntity drop : dropsNear(entity, positive(request, "半径", 12.0))) {
            JsonObject json = new JsonObject();
            json.addProperty("id", drop.getId());
            json.addProperty("uuid", drop.getUUID().toString());
            json.addProperty("x", drop.getX());
            json.addProperty("y", drop.getY());
            json.addProperty("z", drop.getZ());
            json.addProperty("距离", Math.sqrt(entity.distanceToSqr(drop)));
            out.add(json);
        }
        return out.toString();
    }

    /**
     * {@code 可达 实体 x y z}：这个实体有没有路走到那一点，{@code 1} / {@code 0}。
     *
     * <p>只回答「能不能走到」，不替词库决定要不要去——词库拿 {@code 附近掉落物} 的列表，
     * 自己对每个候选问一次，挑出第一个 {@code 1} 的即可。</p>
     */
    private static String reachable(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (entity == null) {
            return "0";
        }
        return canReach(entity,
                decimal(request, "x", entity.getX()),
                decimal(request, "y", entity.getY()),
                decimal(request, "z", entity.getZ())) ? "1" : "0";
    }

    /** {@code 收取掉落物 实体 半径}：把半径内的掉落物塞进该实体（必须是容器），返回装进去的件数。 */
    private static String collectDrops(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (!(entity instanceof Container container)) {
            return "0";
        }
        return Integer.toString(collectAround(entity, container, positive(request, "半径", PICKUP_RANGE)));
    }

    /** {@code 容器空位 实体}：返回还能往里放东西的格数（0 = 满了）。 */
    private static String containerRoom(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        return entity instanceof Container container ? Integer.toString(roomLeft(container)) : "0";
    }

    // ────────────────────────── 上面几个方法的共用实现 ──────────────────────────

    /** 半径内所有「现在就能捡」的掉落物，按距离升序。 */
    private static List<ItemEntity> dropsNear(Entity entity, double radius) {
        List<ItemEntity> drops = new ArrayList<>(entity.level().getEntitiesOfClass(
                ItemEntity.class, entity.getBoundingBox().inflate(radius)));
        drops.removeIf(drop -> drop.isRemoved() || drop.hasPickUpDelay() || drop.getItem().isEmpty());
        drops.sort(java.util.Comparator.comparingDouble(entity::distanceToSqr));
        return drops;
    }

    /** 最近一颗「走得到」的掉落物；一颗都没有返回 null。 */
    private static ItemEntity firstReachable(Entity entity, double radius) {
        for (ItemEntity drop : dropsNear(entity, radius)) {
            if (canReach(entity, drop.getX(), drop.getY(), drop.getZ())) {
                return drop;
            }
        }
        return null;
    }

    /** 贴脸直接算够得着；否则用寻路算一次（Y 取整，必要时抬一层）。 */
    private static boolean canReach(Entity entity, double x, double y, double z) {
        if (entity.distanceToSqr(x, y, z) <= PICKUP_RANGE * PICKUP_RANGE) {
            return true;
        }
        if (!(entity instanceof PathfinderMob mob)) {
            return false;
        }
        Path path = mob.getNavigation().createPath(x, walkY(y), z, 1);
        return path != null && path.canReach();
    }

    /**
     * 寻路用的落点 Y：物品可能停在半砖 / 树叶 / 地毯上，它自己的 Y 是小数，
     * 直接拿去算节点会失败——整数格用 {@code floor(y)}，小数格要抬到上一层才是可站节点。
     */
    private static double walkY(double y) {
        double base = Mth.floor(y);
        return y - base < 0.05 ? base : base + 1.0;
    }

    /** 把半径内的掉落物装进容器，返回装进去的件数（"怎么装"在 {@code Containers}）。 */
    private static int collectAround(Entity owner, Container container, double radius) {
        int moved = 0;
        for (ItemEntity drop : owner.level().getEntitiesOfClass(
                ItemEntity.class, owner.getBoundingBox().inflate(radius))) {
            if (drop.isRemoved() || drop.hasPickUpDelay() || drop.getItem().isEmpty()) {
                continue;
            }
            moved += Containers.absorb(owner, container, drop);
        }
        return moved;
    }

    /** 还能放东西的格数。 */
    private static int roomLeft(Container container) {
        int room = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                room++;
            }
        }
        return room;
    }

    /** {@code 1/n} 概率在 ±3 格内随机挪一挪；返回是否真的下了目标。 */
    private static boolean wander(Entity entity, double speed, int oneInN) {
        if (!(entity instanceof PathfinderMob mob)) {
            return false;
        }
        if (entity.getRandom().nextInt(oneInN) != 0) {
            return false;
        }
        int dx = entity.getRandom().nextInt(7) - 3;
        int dz = entity.getRandom().nextInt(7) - 3;
        if (dx == 0 && dz == 0) {
            return false;
        }
        mob.getNavigation().moveTo(entity.getX() + dx, walkY(entity.getY()), entity.getZ() + dz, speed);
        return true;
    }

    /** 取一个正数参数：缺省 / 非法 / <= 0 时用 fallback（速度、半径这类不能为 0）。 */
    private static double positive(JsonObject request, String key, double fallback) {
        double value = decimal(request, key, fallback);
        return value <= 0 ? fallback : value;
    }

    /** 取 JSON 里的数字参数，缺省 / 非法时用 fallback。 */
    private static double decimal(JsonObject request, String key, double fallback) {
        try {
            var value = request.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String entityPosition(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (entity == null) {
            return "{}";
        }
        JsonObject json = new JsonObject();
        json.addProperty("维度", entity.level().dimension().identifier().toString());
        json.addProperty("x", entity.getX());
        json.addProperty("y", entity.getY());
        json.addProperty("z", entity.getZ());
        return json.toString();
    }

    private static String entityData(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        return entity == null ? "{}" : McSupport.entityJson(entity).toString();
    }

    private static String attribute(MinecraftServer server, JsonObject request) {
        Entity entity = McSupport.entity(server, McSupport.str(request, "实体"));
        if (!(entity instanceof LivingEntity living)) {
            return "";
        }
        String wanted = McSupport.normalized(McSupport.str(request, "属性"));
        if (wanted.isEmpty()) {
            return "";
        }
        try {
            Class<?> attributes = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes");
            for (Field field : attributes.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !McSupport.normalized(field.getName()).equals(wanted)) {
                    continue;
                }
                field.setAccessible(true);
                Object holder = field.get(null);
                for (Method method : living.getClass().getMethods()) {
                    if (!method.getName().equals("getAttributeValue") || method.getParameterCount() != 1) {
                        continue;
                    }
                    Object value = method.invoke(living, holder);
                    return McSupport.text(value);
                }
            }
        } catch (Exception ignored) {
            // 属性名或签名不匹配
        }
        return "";
    }

    /** 26 邻域非空气方块列表；预算限制最多读多少格。 */
    private static String neighbors(MinecraftServer server, JsonObject request) {
        ServerLevel level = McSupport.level(server, McSupport.str(request, "维度"));
        if (level == null) {
            return "[]";
        }
        int x = McSupport.integer(request, "x", 0);
        int y = McSupport.integer(request, "y", 64);
        int z = McSupport.integer(request, "z", 0);
        int budget = Math.max(McSupport.integer(request, "预算", 64), 0);
        String dim = level.dimension().identifier().toString();

        JsonArray out = new JsonArray();
        int reads = 0;
        outer:
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (reads >= budget) {
                        break outer;
                    }
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    reads++;
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("名称", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    item.addProperty("维度", dim);
                    item.addProperty("x", pos.getX());
                    item.addProperty("y", pos.getY());
                    item.addProperty("z", pos.getZ());
                    out.add(item);
                }
            }
        }
        return out.toString();
    }
}
