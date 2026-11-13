package nebula.neoforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 星云词库引擎的 NeoForge 服务端桥接入口。
 *
 * <p>Java 侧只做生命周期、事件转发、命令注册与 {@code 世界} 动作落地；词库逻辑全部在
 * {@code <gameDir>/nebula/<插件名>/main.n}，由 Rust 原生库编译与执行。</p>
 */
@Mod(NebulaMod.MODID)
public final class NebulaMod {
    public static final String MODID = "nebula_bridge";
    private static final Logger LOG = LogManager.getLogger("nebula");

    private static long handle = 0L;

    /** 距离下一次「每秒」事件的 tick 计数；20 tick = 1 秒。 */
    private static int secondTicks = 0;

    /** 插件指令 → 归属插件 / 规范指令名 / 权限等级。 */
    private static final Map<String, CommandOwner> PLUGIN_COMMANDS = new LinkedHashMap<>();

    private record CommandOwner(String plugin, String command, int permission) {
    }

    public NebulaMod() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, NebulaMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, NebulaMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerChatEvent.class, NebulaMod::onChat);
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class, NebulaMod::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, NebulaMod::onLivingHeal);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, NebulaMod::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, NebulaMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Close.class, NebulaMod::onContainerClose);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, NebulaMod::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, NebulaMod::onPlayerLogout);
        // 取TPS / 取MSPT 靠自己统计 tick 间隔与耗时，不依赖随版本变动的服务器内部字段。
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, NebulaMod::onTickPre);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, NebulaMod::onTickPost);
    }

    private static void onTickPre(ServerTickEvent.Pre event) {
        McSupport.onTickPre();
    }

    private static void onTickPost(ServerTickEvent.Post event) {
        McSupport.onTickPost();
        // 飘字上浮：跟随服务器 tick 走，不额外起线程（内部每秒扫一次残留实体）。
        FloatingText.tick(ServerLifecycleHooks.getCurrentServer());
        // 每秒向词库分发一次心跳事件：超时踢人 / 倒计时这类逻辑没有对应的平台事件，
        // 由插件在这个事件里自己数秒。20 tick 一次，与服务器 TPS 同步。
        if (handle == 0L || ++secondTicks < 20) {
            return;
        }
        secondTicks = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("服务器tick", McSupport.tickCount());
        payload.addProperty("在线人数", server.getPlayerList().getPlayers().size());
        JsonObject result = parse(NativeBridge.dispatch(handle, "每秒", payload.toString()));
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    // ────────────────────────────── 生命周期 ──────────────────────────────

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // 上一次运行如果被强杀，飘字实体会随区块一起留在存档里，开服先扫干净。
        FloatingText.sweep(server);
        // 顺带把随 jar 分发的离线文档站点铺到 nebula/_docs（下划线前缀，引擎不扫描）。
        // 放在 create 之前：nebula 目录还不存在时由这一并创建，之后引擎扫到的就是空插件表。
        try {
            String docs = DocsSite.ensure(FMLPaths.GAMEDIR.get());
            if (docs != null) {
                LOG.info("[星云] {}", docs);
            }
        } catch (Throwable t) {
            LOG.warn("[星云] 离线文档站点生成失败（不影响词库）", t);
        }
        try {
            NativeBridge.load();
            handle = NativeBridge.create(
                    FMLPaths.GAMEDIR.get().resolve("nebula").toString());
            if (handle == 0L) {
                LOG.error("[星云] 原生引擎初始化失败");
                return;
            }
            JsonObject result = parse(NativeBridge.loadAll(handle));
            executeActions(result, server);
            EventBridge.refresh(handle);
            LOG.info("[星云] {}", result.has("summary") ? result.get("summary").getAsString() : "已加载");
            LOG.info("[星云] 事件监听：玩家聊天 / 实体受伤（含暴击）/ 实体治疗 / 方块破坏 / 玩家进入 / 每秒心跳 / 插件指令 / 界面点击 / 通用事件桥（按订阅）");
        } catch (Throwable t) {
            LOG.error("[星云] 桥接初始化失败", t);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        EventBridge.clear();
        GuiManager.clearAll();
        PLUGIN_COMMANDS.clear();
        FloatingText.clear();
        if (handle == 0L) {
            return;
        }
        try {
            NativeBridge.destroy(handle);
        } catch (Throwable t) {
            LOG.warn("[星云] 销毁引擎失败", t);
        } finally {
            handle = 0L;
        }
    }

    /** 玩家关掉箱子界面：断开菜单引用，容器内容留给下一次重绘。 */
    private static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GuiManager.onClose(player, event.getContainer());
            InputUi.onClose(player, event.getContainer());
        }
    }

    /** 玩家进服：登录类插件靠这个事件立刻把会话切成「待验证」，不用等下一拍心跳。 */
    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (handle == 0L) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("玩家", playerProfile(player).toString());
        payload.addProperty("玩家名", player.getGameProfile().getName());
        payload.addProperty("管理员", server.getPlayerList().isOp(player.getGameProfile()) ? "是" : "否");
        payload.addProperty("维度", player.level().dimension().location().toString());
        JsonObject result = parse(NativeBridge.dispatch(handle, "玩家进入", payload.toString()));
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GuiManager.onLogout(player);
        }
    }

    // ────────────────────────────── 事件 ──────────────────────────────

    private static void onChat(ServerChatEvent event) {
        if (handle == 0L) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerPlayer player = event.getPlayer();

        JsonObject profile = playerProfile(player);

        JsonObject payload = new JsonObject();
        payload.addProperty("玩家", profile.toString());
        payload.addProperty("玩家名", player.getName().getString());
        payload.addProperty("消息", event.getRawText());
        payload.addProperty("管理员", server.getPlayerList().isOp(player.getGameProfile()) ? "是" : "否");
        payload.addProperty("潜行", String.valueOf(player.isShiftKeyDown()));
        payload.addProperty("维度", profile.get("维度").getAsString());
        payload.addProperty("x", profile.get("x").getAsString());
        payload.addProperty("y", profile.get("y").getAsString());
        payload.addProperty("z", profile.get("z").getAsString());

        JsonObject result = parse(NativeBridge.dispatch(handle, "玩家聊天", payload.toString()));
        if (result.has("cancel") && result.get("cancel").getAsBoolean()) {
            event.setCanceled(true);
        }
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    /**
     * 玩家造成伤害后转发给词库。
     *
     * <p>{@code LivingDamageEvent.Post} 在血量已扣除之后触发：{@code getNewDamage()} 是结算后的
     * 真实伤害（护甲 / 附魔 / 盾牌都已生效），{@code getHealth()} 就是目标的剩余血量。</p>
     */
    private static void onLivingDamage(LivingDamageEvent.Post event) {
        if (handle == 0L) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        LivingEntity victim = event.getEntity();
        // 只转发玩家造成的伤害；getEntity() 对弓箭 / 三叉戟会追溯到发射者。
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        float damage = event.getNewDamage();
        if (damage <= 0.0F) {
            return;
        }
        float remaining = Math.max(victim.getHealth(), 0.0F);

        JsonObject payload = new JsonObject();
        payload.addProperty("攻击者", entityJson(attacker));
        payload.addProperty("攻击者名", attacker.getName().getString());
        payload.addProperty("实体", entityJson(victim));
        payload.addProperty("实体名", victim.getName().getString());
        payload.addProperty("伤害", damage);
        payload.addProperty("剩余", remaining);
        payload.addProperty("最大血量", victim.getMaxHealth());
        payload.addProperty("身高", victim.getBbHeight());
        payload.addProperty("暴击", isCritical(attacker) ? "是" : "否");
        payload.addProperty("原因", event.getSource().getMsgId());
        payload.addProperty("致死", remaining <= 0.0F ? "是" : "否");
        payload.addProperty("维度", victim.level().dimension().location().toString());
        payload.addProperty("x", victim.getX());
        payload.addProperty("y", victim.getY());
        payload.addProperty("z", victim.getZ());

        JsonObject result = parse(NativeBridge.dispatch(handle, "实体受伤", payload.toString()));
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    /**
     * 是否暴击，条件与 {@code Player#attack} 里的 {@code flag2} 完全一致。
     *
     * <p>1.21.1 的伤害来源不携带暴击标记（暴击只是把伤害乘了 1.5 并放暴击粒子），
     * 所以按原版判定复算一次。事件在 {@code Player#attack} 内部触发，此时 {@code fallDistance}
     * 还没被落地逻辑清零，条件仍然成立。</p>
     */
    private static boolean isCritical(ServerPlayer attacker) {
        return attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.hasEffect(MobEffects.BLINDNESS)
                && !attacker.isPassenger()
                && !attacker.isSprinting();
    }

    /**
     * 实体回血后转发给词库。
     *
     * <p>{@code LivingHealEvent} 在血量写入之前触发，所以真实回复量要按上限截断自己算。
     * 已经满血（或这次回复为 0）直接不转发——「满血了回复不用显示」这条在源头就掐掉，
     * 词库不会被动不动满血的玩家刷屏。</p>
     */
    private static void onLivingHeal(LivingHealEvent event) {
        if (handle == 0L) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        float max = entity.getMaxHealth();
        float before = entity.getHealth();
        float gain = Math.min(event.getAmount(), max - before);
        if (gain <= 0.01F) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("实体", entityJson(entity));
        payload.addProperty("实体名", entity.getName().getString());
        payload.addProperty("数值", gain);
        payload.addProperty("之前", before);
        payload.addProperty("剩余", Math.min(before + gain, max));
        payload.addProperty("最大血量", max);
        payload.addProperty("身高", entity.getBbHeight());
        payload.addProperty("原因", healReason(entity));
        payload.addProperty("满血", Math.min(before + gain, max) >= max - 0.01F ? "是" : "否");
        payload.addProperty("维度", entity.level().dimension().location().toString());
        payload.addProperty("x", entity.getX());
        payload.addProperty("y", entity.getY());
        payload.addProperty("z", entity.getZ());

        JsonObject result = parse(NativeBridge.dispatch(handle, "实体治疗", payload.toString()));
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    /**
     * 回复来源的粗分类，够词库决定「要不要显示」。
     *
     * <p>原版没有「谁治的」这类信息：自然回血（饥饿值 / 饱和度）和生命恢复效果都走
     * {@code LivingEntity#heal}。这里按有没有再生效果 + 是不是满饥饿的玩家区分，
     * 让词库能把每次 1 点的自然回血单独关掉。</p>
     */
    private static String healReason(LivingEntity entity) {
        if (entity.hasEffect(MobEffects.REGENERATION)) {
            return "效果";
        }
        if (entity instanceof Player player && player.getFoodData().getFoodLevel() >= 18) {
            return "自然回复";
        }
        return "其它";
    }

    /** 实体快照：只放基础类型，词库用 {@code %实体.名称%} 这类成员访问读取。 */
    private static String entityJson(Entity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("名称", entity.getName().getString());
        json.addProperty("id", entity.getId());
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        json.addProperty("类型", type == null ? "" : type.toString());
        json.addProperty("维度", entity.level().dimension().location().toString());
        json.addProperty("x", entity.getX());
        json.addProperty("y", entity.getY());
        json.addProperty("z", entity.getZ());
        if (entity instanceof LivingEntity living) {
            json.addProperty("血量", living.getHealth());
            json.addProperty("最大血量", living.getMaxHealth());
        }
        return json.toString();
    }

    /**
     * 方块即将被玩家破坏时转发给词库。
     *
     * <p>注入 {@code 玩家} / {@code 方块} 两个对象；词库可按 {@code %玩家.手持物品.名称%} /
     * {@code %玩家.潜行%} / {@code %方块.名称%} 读取。事件在方块真正被移除之前触发，
     * 词库用 {@code $世界.取相邻方块$} 读到的仍是完整结构。</p>
     */
    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (handle == 0L) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        BlockPos pos = event.getPos();
        String dim = level.dimension().location().toString();
        ItemStack held = player.getMainHandItem();
        String heldName = held.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();

        JsonObject profile = playerProfile(player);
        profile.addProperty("模式", modeLabel(player));
        profile.addProperty("潜行", player.isShiftKeyDown() ? "是" : "否");
        JsonObject heldItem = new JsonObject();
        heldItem.addProperty("名称", heldName);
        profile.add("手持物品", heldItem);

        JsonObject coord = new JsonObject();
        coord.addProperty("x", pos.getX());
        coord.addProperty("y", pos.getY());
        coord.addProperty("z", pos.getZ());

        JsonObject block = new JsonObject();
        block.addProperty("名称", BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString());
        block.addProperty("维度", dim);
        block.addProperty("x", pos.getX());
        block.addProperty("y", pos.getY());
        block.addProperty("z", pos.getZ());
        block.addProperty("数据", event.getState().toString());
        block.addProperty("坐标", coord.toString());

        JsonObject payload = new JsonObject();
        payload.addProperty("玩家", profile.toString());
        payload.addProperty("玩家名", player.getName().getString());
        payload.addProperty("管理员", server.getPlayerList().isOp(player.getGameProfile()) ? "是" : "否");
        payload.addProperty("潜行", player.isShiftKeyDown() ? "是" : "否");
        payload.addProperty("模式", modeLabel(player));
        payload.addProperty("工具", heldName);
        payload.addProperty("维度", dim);
        payload.addProperty("坐标", coord.toString());
        payload.addProperty("方块", block.toString());
        payload.addProperty("x", pos.getX());
        payload.addProperty("y", pos.getY());
        payload.addProperty("z", pos.getZ());

        JsonObject result = parse(NativeBridge.dispatch(handle, "方块破坏", payload.toString()));
        if (result.has("cancel") && result.get("cancel").getAsBoolean()) {
            event.setCanceled(true);
        }
        broadcastOutputs(server, result);
        executeActions(result, server);
    }

    /** 玩家模式的中文标签，与基岩版词库约定一致。 */
    private static String modeLabel(ServerPlayer player) {
        GameType type = player.gameMode.getGameModeForPlayer();
        return switch (type) {
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
            default -> "生存";
        };
    }

    // ────────────────────────────── 命令 ──────────────────────────────

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("nebula")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reply(ctx,
                                "用法：/nebula list|load|reload|unload|debug|undebug <插件名|--all>"))
                        .then(Commands.literal("list").executes(ctx -> invoke(ctx, "list", "")))
                        .then(target("load"))
                        .then(target("reload"))
                        .then(target("debug"))
                        .then(target("undebug"))
                        .then(target("unload")));
        // 服务端重启会重建 dispatcher，旧映射必须清掉；插件指令由 [f]初始化 重新注册。
        PLUGIN_COMMANDS.clear();
    }

    /**
     * 词库在 {@code [f]初始化} 里用 {@code $世界.注册指令$} 声明指令，宿主在这里落到 Brigadier。
     *
     * <p>重复注册按「插件 + 规范名」幂等；权限变化会拆掉旧节点重注册。别名与现有指令
     * 冲突时跳过并告警。</p>
     */
    private static void registerPluginCommand(MinecraftServer server, JsonObject action) {
        if (server == null || !action.has("指令")) {
            return;
        }
        String plugin = pluginOf(action);
        String canonical = action.get("指令").getAsString();
        int permission = action.has("权限") ? action.get("权限").getAsInt() : 0;
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (String name : commandNames(action, canonical)) {
            CommandOwner exists = PLUGIN_COMMANDS.get(name);
            if (exists != null) {
                if (!exists.plugin().equals(plugin)) {
                    LOG.warn("[星云] 指令 /{} 已被插件 {} 占用，{} 跳过", name, exists.plugin(), plugin);
                    continue;
                }
                if (!exists.command().equals(canonical) || exists.permission() == permission) {
                    continue;
                }
                // 同一个插件换了权限：拆掉旧节点，下面重新注册。
                removeCommandNode(dispatcher, name);
                PLUGIN_COMMANDS.remove(name);
            }
            if (dispatcher.getRoot().getChild(name) != null) {
                LOG.warn("[星云] 指令 /{} 与现有指令冲突，插件 {} 跳过", name, plugin);
                continue;
            }
            CommandOwner owner = new CommandOwner(plugin, canonical, permission);
            dispatcher.register(pluginCommand(name, owner));
            PLUGIN_COMMANDS.put(name, owner);
            changed = true;
            LOG.info("[星云] 插件 {} 注册指令 /{}（权限 {}）", plugin, name, permission);
        }
        if (changed) {
            resendCommands(server);
        }
    }

    /** {@code $世界.注销指令$}：只删该插件自己注册的入口。 */
    private static void unregisterPluginCommand(MinecraftServer server, JsonObject action) {
        if (server == null || !action.has("指令")) {
            return;
        }
        String plugin = pluginOf(action);
        String canonical = action.get("指令").getAsString();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (String name : commandNames(action, canonical)) {
            CommandOwner owner = PLUGIN_COMMANDS.get(name);
            if (owner != null && owner.plugin().equals(plugin)) {
                removeCommandNode(dispatcher, name);
                PLUGIN_COMMANDS.remove(name);
                changed = true;
                LOG.info("[星云] 插件 {} 注销指令 /{}", plugin, name);
            }
        }
        if (changed) {
            resendCommands(server);
        }
    }

    private static List<String> commandNames(JsonObject action, String canonical) {
        List<String> names = new ArrayList<>();
        names.add(canonical);
        if (action.has("别名") && action.get("别名").isJsonArray()) {
            for (JsonElement alias : action.getAsJsonArray("别名")) {
                String name = alias.getAsString();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Brigadier 没有公开删除 API，根节点的 children 是 map 视图，按名字移除即可。 */
    private static void removeCommandNode(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.getRoot().getChildren().removeIf(node -> node.getName().equals(name));
    }

    private static void resendCommands(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pluginCommand(String name, CommandOwner owner) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(owner.permission()))
                .executes(ctx -> runPluginCommand(ctx, owner, ""))
                .then(Commands.argument("参数", StringArgumentType.greedyString())
                        .executes(ctx -> runPluginCommand(ctx,
                                owner, StringArgumentType.getString(ctx, "参数").trim())));
    }

    /** 插件指令 → {@code [指令]} 词条（只送给声明该指令的插件）。 */
    private static int runPluginCommand(CommandContext<CommandSourceStack> ctx, CommandOwner owner, String args) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该指令只能由玩家执行"));
            return 0;
        }
        if (handle == 0L) {
            source.sendFailure(Component.literal("星云桥接未初始化"));
            return 0;
        }
        JsonObject payload = playerProfile(player);
        payload.addProperty("玩家名", player.getGameProfile().getName());
        payload.addProperty("指令", owner.command());
        payload.addProperty("参数", args);
        payload.addProperty("管理员", source.hasPermission(2) ? "是" : "否");
        // 按玩家实际输入的入口名匹配 [指令]商店|shop；payload 里的「指令」仍是规范名。
        String typed = ctx.getInput().split("\\s+", 2)[0];
        if (typed.startsWith("/")) {
            typed = typed.substring(1);
        }
        JsonObject result;
        try {
            result = parse(NativeBridge.dispatchCommand(handle, owner.plugin(), typed, payload.toString()));
        } catch (Throwable t) {
            LOG.error("[星云] 指令 {} 分发失败", owner.command(), t);
            source.sendFailure(Component.literal("星云插件指令执行失败：" + t.getMessage()));
            return 0;
        }
        executeActions(result, source.getServer());
        sendOutputs(source, result);
        if (result.has("ok") && !result.get("ok").getAsBoolean()) {
            String summary = result.has("summary") ? result.get("summary").getAsString() : "插件未处理该指令";
            source.sendFailure(Component.literal(summary));
            return 0;
        }
        return 1;
    }

    /** 把词库输出（事件正文 / 函数返回值）发回指令来源。 */
    private static void sendOutputs(CommandSourceStack source, JsonObject result) {
        if (!result.has("outputs") || !result.get("outputs").isJsonArray()) {
            return;
        }
        for (JsonElement element : result.getAsJsonArray("outputs")) {
            JsonObject item = element.getAsJsonObject();
            String plugin = item.has("插件") ? item.get("插件").getAsString() : "";
            String text = item.has("文本") ? item.get("文本").getAsString() : "";
            source.sendSuccess(() -> Component.literal("[" + plugin + "] " + text), false);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> target(String op) {
        // 插件名允许中文，word() 只认 ASCII，会把中文读成空串并报“尾随数据”，故用 greedyString。
        return Commands.literal(op).then(
                Commands.argument("目标", StringArgumentType.greedyString())
                        .executes(ctx -> invoke(ctx, op, StringArgumentType.getString(ctx, "目标").trim())));
    }

    private static int invoke(CommandContext<CommandSourceStack> ctx, String op, String target) {
        CommandSourceStack source = ctx.getSource();
        if (handle == 0L) {
            source.sendFailure(Component.literal("星云桥接未初始化"));
            return 0;
        }
        String raw;
        try {
            raw = switch (op) {
                case "list" -> NativeBridge.list(handle);
                case "load" -> NativeBridge.load(handle, target);
                case "reload" -> NativeBridge.reload(handle, target);
                case "debug" -> NativeBridge.debug(handle, target);
                case "undebug" -> NativeBridge.undebug(handle, target);
                default -> NativeBridge.unload(handle, target);
            };
        } catch (Throwable t) {
            LOG.error("[星云] 命令 {} 执行失败", op, t);
            source.sendFailure(Component.literal("星云命令执行失败：" + t.getMessage()));
            return 0;
        }
        JsonObject result = parse(raw);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        executeActions(result, server);
        if (!op.equals("list")) {
            EventBridge.refresh(handle);
        }
        String summary = result.has("summary") ? result.get("summary").getAsString() : raw;
        boolean ok = !result.has("ok") || result.get("ok").getAsBoolean();
        if (ok) {
            source.sendSuccess(() -> Component.literal(summary), false);
            return 1;
        }
        source.sendFailure(Component.literal(summary));
        return 0;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // ────────────────────────────── 世界 动作落地 ──────────────────────────────

    static void broadcastOutputs(MinecraftServer server, JsonObject result) {
        if (server == null || !result.has("outputs") || !result.get("outputs").isJsonArray()) {
            return;
        }
        for (JsonElement element : result.getAsJsonArray("outputs")) {
            JsonObject item = element.getAsJsonObject();
            String plugin = item.has("插件") ? item.get("插件").getAsString() : "";
            String text = item.has("文本") ? item.get("文本").getAsString() : "";
            Component message = Component.literal("[" + plugin + "] " + text);
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    static void executeActions(JsonObject result, MinecraftServer server) {
        if (server == null || !result.has("actions")) {
            return;
        }
        JsonElement actions = result.get("actions");
        if (!actions.isJsonArray()) {
            return;
        }
        for (JsonElement element : actions.getAsJsonArray()) {
            JsonObject action = element.getAsJsonObject();
            String op = action.has("op") ? action.get("op").getAsString() : "";
            String text = action.has("文本") ? action.get("文本").getAsString() : "";
            switch (op) {
                case "提示" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(text));
                    }
                }
                case "动作栏" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        player.displayClientMessage(Component.literal(text), true);
                    }
                }
                case "广播" -> server.getPlayerList()
                        .broadcastSystemMessage(Component.literal(text), false);
                case "执行命令" -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack(),
                        action.get("命令").getAsString());
                case "破坏方块" -> breakBlock(server, action);
                case "扣耐久" -> wearTool(server, action);
                case "注册指令" -> registerPluginCommand(server, action);
                case "注销指令" -> unregisterPluginCommand(server, action);
                // 下面五个是界面对象写法内部发出的动作 op，保留分支但不再暴露成 `世界` 方法。
                case "清空界面" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        GuiManager.clear(player, pluginOf(action),
                                action.has("行数") ? action.get("行数").getAsInt() : 3);
                    }
                }
                case "设界面物品" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        GuiManager.setItem(player, pluginOf(action),
                                action.get("槽位").getAsInt(),
                                action.get("物品").getAsString(),
                                action.has("数量") ? action.get("数量").getAsInt() : 1,
                                action.has("名称") ? action.get("名称").getAsString() : "",
                                action.has("说明") ? action.get("说明").getAsString() : "",
                                action.has("发光") && action.get("发光").getAsBoolean());
                    }
                }
                case "设界面数据" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        GuiManager.setData(player, pluginOf(action),
                                action.has("数据") ? action.get("数据").getAsString() : "");
                    }
                }
                case "开界面" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        GuiManager.open(player, pluginOf(action),
                                action.has("标题") ? action.get("标题").getAsString() : "",
                                action.has("界面ID") ? action.get("界面ID").getAsLong() : 0L);
                    }
                }
                case "点击消息" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        ClickChat.send(player, action.has("段") ? action.get("段").getAsString() : "");
                    }
                }
                case "输入界面" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        InputUi.open(player,
                                action.has("标题") ? action.get("标题").getAsString() : "",
                                action.has("初始") ? action.get("初始").getAsString() : "",
                                action.has("界面ID") ? action.get("界面ID").getAsLong() : 0L);
                    }
                }
                case "关界面" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        GuiManager.close(player);
                    }
                }
                case "音效" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        playSound(player, action);
                    }
                }
                case "悬浮字" -> FloatingText.spawn(server, action);
                case "标题" -> {
                    ServerPlayer player = playerOf(server, action);
                    if (player != null) {
                        showTitle(player, action);
                    }
                }
                case "给物品" -> giveItem(server, action);
                case "扣物品" -> takeItem(server, action);
                // 映射表里的其余动作统一交给 McActions（命令 / 稳定 API 落地）。
                default -> McActions.apply(server, action);
            }
        }
    }

    private static String pluginOf(JsonObject action) {
        return action.has("插件") ? action.get("插件").getAsString() : "";
    }

    private static ServerPlayer playerOf(MinecraftServer server, JsonObject action) {
        if (server == null || !action.has("玩家")) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(action.get("玩家").getAsString());
    }

    /** 只给这名玩家发提示音，不打扰附近玩家。 */
    private static void playSound(ServerPlayer player, JsonObject action) {
        ResourceLocation id = ResourceLocation.tryParse(
                action.has("音效") ? action.get("音效").getAsString() : "");
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(id);
        if (sound == null) {
            LOG.warn("[星云] 未知音效：{}", id);
            return;
        }
        float volume = action.has("音量") ? (float) action.get("音量").getAsDouble() : 1.0F;
        float pitch = action.has("音调") ? (float) action.get("音调").getAsDouble() : 1.0F;
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    /** 屏幕中央大标题（购买成功 / 余额不足这类强反馈）。 */
    private static void showTitle(ServerPlayer player, JsonObject action) {
        String title = action.has("主标题") ? action.get("主标题").getAsString() : "";
        String subtitle = action.has("副标题") ? action.get("副标题").getAsString() : "";
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 45, 10));
        if (!subtitle.isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        }
        if (!title.isEmpty()) {
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        }
    }

    /** 按最大堆叠数分批给物品；背包塞不下就掉在脚下。 */
    private static void giveItem(MinecraftServer server, JsonObject action) {
        ServerPlayer player = playerOf(server, action);
        if (player == null || !action.has("物品")) {
            return;
        }
        Item item = McApi.item(action.get("物品").getAsString());
        if (item == Items.AIR) {
            return;
        }
        int remaining = Math.max(1, action.has("数量") ? action.get("数量").getAsInt() : 1);
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        Inventory inventory = player.getInventory();
        while (remaining > 0) {
            int count = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, count);
            if (!inventory.add(stack)) {
                player.drop(stack, false);
            }
            remaining -= count;
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** 从背包（含副手 / 盔甲）扣物品；数量不足时能扣多少扣多少。 */
    private static void takeItem(MinecraftServer server, JsonObject action) {
        ServerPlayer player = playerOf(server, action);
        if (player == null || !action.has("物品")) {
            return;
        }
        Item item = McApi.item(action.get("物品").getAsString());
        if (item == Items.AIR) {
            return;
        }
        int remaining = Math.max(1, action.has("数量") ? action.get("数量").getAsInt() : 1);
        Inventory inventory = player.getInventory();
        remaining = takeFrom(inventory.items, item, remaining);
        remaining = takeFrom(inventory.offhand, item, remaining);
        takeFrom(inventory.armor, item, remaining);
        player.inventoryMenu.broadcastChanges();
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

    /**
     * 按方块掉落表清掉一格：走 {@code setblock … destroy} 命令，由游戏负责掉落物，
     * 且不会再次触发方块破坏事件。
     */
    private static void breakBlock(MinecraftServer server, JsonObject action) {
        String dim = action.has("维度") ? action.get("维度").getAsString() : "";
        if (dim.isEmpty() || !action.has("x") || !action.has("y") || !action.has("z")) {
            return;
        }
        String command = "execute in " + dim + " run setblock "
                + action.get("x").getAsInt() + " "
                + action.get("y").getAsInt() + " "
                + action.get("z").getAsInt() + " minecraft:air destroy";
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** 给玩家主手工具扣耐久；耐久耗尽时直接消耗整件物品并刷新背包。 */
    private static void wearTool(MinecraftServer server, JsonObject action) {
        if (!action.has("玩家") || !action.has("点数")) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(action.get("玩家").getAsString());
        if (player == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }
        int damage = stack.getDamageValue() + action.get("点数").getAsInt();
        if (damage >= stack.getMaxDamage()) {
            stack.shrink(1);
        } else {
            stack.setDamageValue(damage);
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** 玩家快照：事件与界面点击共用的基础字段。 */
    static JsonObject playerProfile(ServerPlayer player) {
        JsonObject profile = new JsonObject();
        profile.addProperty("名称", player.getName().getString());
        profile.addProperty("uuid", player.getUUID().toString());
        profile.addProperty("维度", player.level().dimension().location().toString());
        profile.addProperty("x", player.getX());
        profile.addProperty("y", player.getY());
        profile.addProperty("z", player.getZ());
        return profile;
    }

    static long handle() {
        return handle;
    }

    static Logger log() {
        return LOG;
    }

    /** 界面点击等宿主主动分发的结果：落地动作，并把正文回给该玩家。 */
    static void afterDispatch(ServerPlayer player, JsonObject result) {
        executeActions(result, ServerLifecycleHooks.getCurrentServer());
        sendOutputs(player.createCommandSourceStack(), result);
    }

    static JsonObject parse(String json) {
        if (json == null || json.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOG.warn("[星云] 无法解析桥接返回值：{}", json);
            return new JsonObject();
        }
    }
}
