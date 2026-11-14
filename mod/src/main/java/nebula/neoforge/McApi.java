package nebula.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.server.players.NameAndId;

/**
 * Rust 词库 {@code 世界} 查询回调。
 *
 * <p>所有查询都在服务端主线程同步执行：Rust 在事件分发中调用 {@link #query(String)}，
 * Java 读世界后把结果按文本 / JSON 文本返回，词库据此继续计算。动作类走
 * {@link NativeBridge#dispatch} 返回的 actions 队列，由 {@link McActions} 落地。</p>
 *
 * <p>完整方法清单与参数约定见 {@code mod/docs/mc-api-map.md}。</p>
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
            case "取环境变量" -> McSupport.env(McSupport.str(request, "名称"));
            case "取本地时间" -> McSupport.localTime();
            case "取模拟暂停" -> tickFrozen(server);
            // 实体
            case "取实体列表" -> entityList(server, request);
            case "取实体坐标" -> entityPosition(server, request);
            case "取实体数据" -> entityData(server, request);
            case "实体有标签" -> entityTag(server, McSupport.str(request, "实体"),
                    McSupport.str(request, "标签"));
            case "取实体属性" -> attribute(server, request);
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
        BlockPos blockPos = null;
        Object pos = McSupport.invoke(level, "getSharedSpawnPos");
        if (pos instanceof BlockPos shared) {
            blockPos = shared;
        } else {
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
        json.addProperty("维度", level == null ? "" : level.dimension().identifier().toString());
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
        final int maxEntities = limit;
        JsonArray out = new JsonArray();
        Object levels;
        if (dimension.isBlank()) {
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
                if (out.size() >= maxEntities) {
                    return out.toString();
                }
                out.add(McSupport.entityJson(entity));
            }
        }
        return out.toString();
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
