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
import net.minecraft.resources.Identifier;
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
import nebula.neoforge.content.ItemRegistry;
import nebula.neoforge.content.MobRegistry;
import nebula.neoforge.content.MobSync;
import nebula.neoforge.content.NebulaBlocks;
import nebula.neoforge.content.NebulaCreativeTab;
import nebula.neoforge.content.NebulaEntities;
import nebula.neoforge.content.NebulaItems;
import nebula.neoforge.snippet.JavaSnippet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionCheck;

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
    /**
     * 进服后延迟补发指令树的倒计时（tick），键是玩家 UUID。
     *
     * <p>客户端认识哪些指令，只取决于 JOIN 时那一个 {@code ClientboundCommandsPacket}。
     * 它一旦丢失或客户端尚未就绪，整棵插件指令在客户端本地就解析不出来：
     * dialog 按钮走 {@code run_command} → {@code verifyCommand} 返回 PARSE_ERRORS →
     * 客户端弹「确认仍要发送」而不是直接发包（表现就是「这条指令不存在」）。
     * 补发一次成本极低，却能兜住整类问题。</p>
     */
    private static final java.util.Map<java.util.UUID, Integer> PENDING_COMMAND_RESEND =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 插件指令 → 归属插件 / 规范指令名 / 权限等级。 */
    private static final Map<String, CommandOwner> PLUGIN_COMMANDS = new LinkedHashMap<>();
    /**
     * 本轮编译中每个插件声明过的指令名。
     *
     * <p>和 {@link MobRegistry} 的「按批次整体替换」同一套思路：插件删掉一行
     * {@code $世界.注册指令$} 后不需要写注销，加载器会在收口时把该插件名下
     * 本批没声明的指令摘掉。</p>
     */
    private static final Map<String, java.util.Set<String>> COMMAND_BATCH = new LinkedHashMap<>();

    /**
     * 插件指令 → 归属插件 / 规范指令名 / 权限等级 / 声明的子参数。
     *
     * <p>{@code subs} 决定客户端 Tab 能不能看见后续参数：只管注册 literal 子节点，
     * 命中后原样把该词当 {@code %参数%} 交给词库，所以词库判断一个字都不用改。</p>
     */
    private record CommandOwner(String plugin, String command, int permission, List<String> subs) {
    }

    public NebulaMod(IEventBus modBus) {
        // 双端内容：真·独立实体类型在 MOD 总线登记，属性走 EntityAttributeCreationEvent。
        NebulaEntities.ENTITY_TYPES.register(modBus);
        modBus.addListener(NebulaEntities::registerAttributes);
        NebulaBlocks.BLOCKS.register(modBus);
        // 「星云物品」创造页标签：内容全由词库注册表填，标签只是编译期的一个壳。
        NebulaCreativeTab.TABS.register(modBus);
        // 通用刷怪蛋：一个 id 吃下所有品种 —— 实体类型走 ItemStack 的 entity_data 组件，
        // 所以词库加品种不需要新增注册项，且自动进原版「生成蛋」页、白拿原版 useOn 全套行为。
        NebulaItems.ITEMS.register(modBus);
        modBus.addListener(nebula.neoforge.content.CreativeItems::add);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, NebulaMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, NebulaMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerChatEvent.class, NebulaMod::onChat);
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class, NebulaMod::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, NebulaMod::onLivingHeal);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, NebulaMod::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, NebulaMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Close.class, NebulaMod::onContainerClose);
        // 掉落物逻辑无限堆：接住拾取，漏斗搬空可见栈后补回剩余总量。
        NeoForge.EVENT_BUS.addListener(ItemEntityPickupEvent.Pre.class, DropStack::onPickup);
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, DropStack::onLeaveLevel);
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
        // 逻辑无限堆：先合并 / 同步总量，数量名牌再按最新总量写字。
        DropStack.tick(ServerLifecycleHooks.getCurrentServer());
        // 飘字上浮：跟随服务器 tick 走，不额外起线程（内部每秒扫一次残留实体）。
        FloatingText.tick(ServerLifecycleHooks.getCurrentServer());
        // 掉落物数量名牌：只改实体 CustomName，不动物品栈（拾取 / 漏斗不受影响）。
        DropLabel.tick(ServerLifecycleHooks.getCurrentServer());
        tickCommandResend();
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

    /** 进服 40 tick 后给该玩家重发一次指令树；队列空时什么都不做。 */
    private static void tickCommandResend() {
        if (PENDING_COMMAND_RESEND.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Integer left = PENDING_COMMAND_RESEND.get(player.getUUID());
            if (left == null) {
                continue;
            }
            if (left <= 1) {
                PENDING_COMMAND_RESEND.remove(player.getUUID());
                server.getCommands().sendCommands(player);
            } else {
                PENDING_COMMAND_RESEND.put(player.getUUID(), left - 1);
            }
        }
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
        // 资源包投送：客户端不装 mod，靠原版资源包机制拿到自定义模型/贴图。
        // 资源全部来自各插件的 资源/，pack.mcmeta 由 NebulaPack 内建，磁盘上无骨架目录。
        try {
            String pack = NebulaPack.start(server);
            if (pack != null) {
                LOG.info("[星云] {}", pack);
            }
        } catch (Throwable t) {
            LOG.warn("[星云] 资源包不可用（不影响词库）", t);
        }
        // 皮肤记录：与词库引擎无关，玩家进服时按记录重放（正版 textures / 本地 PNG）。
        try {
            SkinService.start();
        } catch (Throwable t) {
            LOG.warn("[星云] 皮肤服务不可用（不影响词库）", t);
        }
        try {
            NativeBridge.load();
            handle = NativeBridge.create(
                    FMLPaths.GAMEDIR.get().resolve("nebula").toString(),
                    WorldSpecs.load());
            if (handle == 0L) {
                LOG.error("[星云] 原生引擎初始化失败");
                return;
            }
            JsonObject result = parse(NativeBridge.loadAll(handle));
            COMMAND_BATCH.clear();
            executeActions(result, server);
            // 指令 + 生物 / 物品对象：开服全量加载也走「按批次收口」，然后重建资源包（此时没有玩家在线）。
            MobRegistry.markReady();
            if (result.has("批次")) {
                commitPluginCommands(server);
                MobRegistry.commit(result.get("批次").getAsLong());
                ItemRegistry.commit(result.get("批次").getAsLong());
            }
            MobSync.markDirty();
            MobSync.sync(server);
            EventBridge.refresh(handle);
            LOG.info("[星云] {}", result.has("summary") ? result.get("summary").getAsString() : "已加载");
            logDiagnostics(result);
            LOG.info("[星云] 事件监听：玩家聊天 / 实体受伤（含暴击）/ 实体治疗 / 方块破坏 / 玩家进入 / 每秒心跳 / 插件指令 / 界面点击 / 通用事件桥（按订阅）");
        } catch (Throwable t) {
            LOG.error("[星云] 桥接初始化失败", t);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        NebulaPack.stop();
        SkinService.stop();
        EventBridge.clear();
        GuiManager.clearAll();
        PLUGIN_COMMANDS.clear();
        FloatingText.clear();
        DropStack.clear();
        // 片段对象持有玩家 / 世界引用，停机时必须主动放掉，否则下次开服还挂在旧实例上。
        JavaSnippet.forgetAll();
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
        // 资源包与引擎无关，放在 handle 判断之前：即使词库挂了，外观也该正常投送。
        if (event.getEntity() instanceof ServerPlayer joining) {
            NebulaPack.push(joining);
            // 皮肤：先恢复上次保存的选择，再走后续的词库事件（不依赖引擎是否就绪）。
            try {
                SkinService.onJoin(ServerLifecycleHooks.getCurrentServer(), joining);
            } catch (Throwable t) {
                LOG.warn("[星云] 皮肤重放失败：{}", t.toString());
            }
            // 指令树补发：不依赖引擎是否就绪，先排进队列，40 tick 后再发一次。
            PENDING_COMMAND_RESEND.put(joining.getUUID(), 40);
            // 欢迎语 / 动作栏 / 粒子由词库插件 [事件]玩家进入 决定，宿主不写死任何玩法表现。
        }
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
        payload.addProperty("玩家名", player.getGameProfile().name());
        payload.addProperty("管理员", server.getPlayerList().isOp(new NameAndId(player.getGameProfile())) ? "是" : "否");
        payload.addProperty("维度", player.level().dimension().identifier().toString());
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
        payload.addProperty("管理员", server.getPlayerList().isOp(new NameAndId(player.getGameProfile())) ? "是" : "否");
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
     * 实体掉血后转发给词库（不限伤害来源）。
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
        float damage = event.getNewDamage();
        if (damage <= 0.0F) {
            return;
        }
        float remaining = Math.max(victim.getHealth(), 0.0F);

        // 任何掉血都转发：来源可能是玩家、其它实体（怪物互殴 / 弹射物），也可能
        // 根本没有实体（摔落 / 岩浆 / /damage 命令）。getEntity() 对弓箭 / 三叉戟
        // 会追溯到发射者；只有玩家来源才有「暴击」这个概念。
        Entity source = event.getSource().getEntity();
        ServerPlayer attacker = source instanceof ServerPlayer player ? player : null;
        String sourceName = source != null
                ? source.getName().getString()
                : event.getSource().getMsgId();

        JsonObject payload = new JsonObject();
        payload.addProperty("攻击者", source == null ? "{}" : entityJson(source));
        payload.addProperty("攻击者名", sourceName);
        payload.addProperty("实体", entityJson(victim));
        payload.addProperty("实体名", victim.getName().getString());
        payload.addProperty("伤害", damage);
        payload.addProperty("剩余", remaining);
        payload.addProperty("最大血量", victim.getMaxHealth());
        payload.addProperty("身高", victim.getBbHeight());
        payload.addProperty("暴击", attacker != null && isCritical(attacker) ? "是" : "否");
        payload.addProperty("原因", event.getSource().getMsgId());
        payload.addProperty("致死", remaining <= 0.0F ? "是" : "否");
        payload.addProperty("维度", victim.level().dimension().identifier().toString());
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
     * <p>1.21.11 的伤害来源不携带暴击标记（暴击只是把伤害乘了 1.5 并放暴击粒子），
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
        payload.addProperty("维度", entity.level().dimension().identifier().toString());
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
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        json.addProperty("类型", type == null ? "" : type.toString());
        json.addProperty("维度", entity.level().dimension().identifier().toString());
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
        String dim = level.dimension().identifier().toString();
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
        payload.addProperty("管理员", server.getPlayerList().isOp(new NameAndId(player.getGameProfile())) ? "是" : "否");
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
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> reply(ctx,
                                "用法：/nebula list|load|unload|debug|undebug <插件名|--all>"))
                        .then(Commands.literal("list").executes(ctx -> invoke(ctx, "list", "")))
                        .then(target("load"))
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
        List<String> subs = commandSubs(action);
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (String name : commandNames(action, canonical)) {
            CommandOwner exists = PLUGIN_COMMANDS.get(name);
            if (exists != null) {
                if (!exists.plugin().equals(plugin)) {
                    LOG.warn("[星云] 指令 /{} 已被插件 {} 占用，{} 跳过", name, exists.plugin(), plugin);
                    continue;
                }
                // 本插件已注册过：先记为「本批已声明」，再决定要不要重注册。
                COMMAND_BATCH.computeIfAbsent(plugin, key -> new java.util.LinkedHashSet<>()).add(name);
                if (exists.command().equals(canonical)
                        && exists.permission() == permission
                        && exists.subs().equals(subs)) {
                    continue;
                }
                // 同一个插件换了权限或子参数：拆掉旧节点，下面重新注册。
                removeCommandNode(dispatcher, name);
                PLUGIN_COMMANDS.remove(name);
            }
            if (dispatcher.getRoot().getChild(name) != null) {
                LOG.warn("[星云] 指令 /{} 与现有指令冲突，插件 {} 跳过", name, plugin);
                continue;
            }
            CommandOwner owner = new CommandOwner(plugin, canonical, permission, subs);
            dispatcher.register(pluginCommand(name, owner));
            PLUGIN_COMMANDS.put(name, owner);
            COMMAND_BATCH.computeIfAbsent(plugin, key -> new java.util.LinkedHashSet<>()).add(name);
            changed = true;
            LOG.info("[星云] 插件 {} 注册指令 /{}（权限 {}{}）", plugin, name, permission,
                    subs.isEmpty() ? "" : "，子参数 " + String.join("/", subs));
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

    /**
     * 指令对账：本批没声明的指令自动下线。
     *
     * <p>插件删掉一行 {@code $世界.注册指令$} 后不需要写注销 —— 加载器在收口时
     * 把该插件名下、本批未出现的指令摘掉。和 {@link MobRegistry#commit(long)}
     * 是同一套「按批次整体替换」。</p>
     */
    private static void commitPluginCommands(MinecraftServer server) {
        if (server == null || COMMAND_BATCH.isEmpty()) {
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (Map.Entry<String, java.util.Set<String>> entry : COMMAND_BATCH.entrySet()) {
            String plugin = entry.getKey();
            java.util.Set<String> declared = entry.getValue();
            for (String name : new ArrayList<>(PLUGIN_COMMANDS.keySet())) {
                CommandOwner owner = PLUGIN_COMMANDS.get(name);
                if (owner == null || !owner.plugin().equals(plugin) || declared.contains(name)) {
                    continue;
                }
                removeCommandNode(dispatcher, name);
                PLUGIN_COMMANDS.remove(name);
                changed = true;
                LOG.info("[星云] 插件 {} 指令下线 /{}（本批未声明）", plugin, name);
            }
        }
        COMMAND_BATCH.clear();
        if (changed) {
            resendCommands(server);
        }
    }

    /**
     * 卸载：按插件归属整批注销指令（{@code --all} 清全部）。
     *
     * <p>这就是「卸载不再需要插件写 {@code [f]卸载}」的落点 —— 指令注册时已记了
     * {@link CommandOwner#plugin()}，加载器直接按归属扫一遍即可，不需要词库配合。</p>
     */
    private static void unregisterPluginCommands(MinecraftServer server, String target) {
        if (server == null) {
            return;
        }
        boolean all = "--all".equals(target);
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (String name : new ArrayList<>(PLUGIN_COMMANDS.keySet())) {
            CommandOwner owner = PLUGIN_COMMANDS.get(name);
            if (owner == null || (!all && !owner.plugin().equals(target))) {
                continue;
            }
            removeCommandNode(dispatcher, name);
            PLUGIN_COMMANDS.remove(name);
            changed = true;
        }
        if (changed) {
            LOG.info("[星云] 按归属注销指令：{}", all ? "全部插件" : target);
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

    /** `参数=` 声明的子参数：只用于建 literal 子节点（客户端 Tab 补全），不参与路由。 */
    private static List<String> commandSubs(JsonObject action) {
        List<String> subs = new ArrayList<>();
        if (action.has("子参数") && action.get("子参数").isJsonArray()) {
            for (JsonElement element : action.getAsJsonArray("子参数")) {
                String name = element.getAsString().trim();
                if (!name.isEmpty() && !subs.contains(name)) {
                    subs.add(name);
                }
            }
        }
        return subs;
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

    /** 词库注册指令写的 0~4 权限等级 → 1.21.11 的 PermissionCheck 对象。 */
    private static PermissionCheck permissionCheck(int level) {
        return switch (level) {
            case 4 -> Commands.LEVEL_OWNERS;
            case 3 -> Commands.LEVEL_ADMINS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 1 -> Commands.LEVEL_MODERATORS;
            default -> Commands.LEVEL_ALL;
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pluginCommand(String name, CommandOwner owner) {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(name)
                .executes(ctx -> runPluginCommand(ctx, owner, ""));
        // 声明过的子参数建成 literal 子节点：客户端 Tab 才会列出「/商店 帮助」「/商店 余额」。
        // 命中后把该词原样当 %参数% 交给词库，所以 `如果:%参%==帮助` 一个字都不用改；
        // 子节点后面再挂 greedy，`/商店 帮助 更多` 也能整段透传。
        for (String sub : owner.subs()) {
            builder.then(Commands.literal(sub)
                    .executes(ctx -> runPluginCommand(ctx, owner, sub))
                    .then(Commands.argument("参数", StringArgumentType.greedyString())
                            .executes(ctx -> runPluginCommand(ctx, owner,
                                    (sub + " " + StringArgumentType.getString(ctx, "参数")).trim()))));
        }
        builder.then(Commands.argument("参数", StringArgumentType.greedyString())
                .executes(ctx -> runPluginCommand(ctx,
                        owner, StringArgumentType.getString(ctx, "参数").trim())));
        // 权限 0 表示「谁都能用」，干脆不挂 requires：1.21.11 换了新权限体系，
        // LEVEL_ALL 虽然恒真（AlwaysPass），但客户端 verifyCommand 会拿「受限 source」
        // 再解析一遍，只要节点带 requires 就有可能被判成 permissions_required，
        // 于是弹「确认发送」。没有 requires 才是真的谁都能用。
        if (owner.permission() > 0) {
            builder.requires(Commands.hasPermission(permissionCheck(owner.permission())));
        }
        return builder;
    }

    /** 插件指令 → {@code [指令]} 词条（只送给声明该指令的插件）。 */
    private static int runPluginCommand(CommandContext<CommandSourceStack> ctx, CommandOwner owner, String args) {
        CommandSourceStack source = ctx.getSource();
        // 加载器已注销的入口：Brigadier 里可能还留着旧节点（命令树只增不清），
        // 这里以 `PLUGIN_COMMANDS` 为准拒绝执行，保证 unload / 删声明立刻失效。
        if (PLUGIN_COMMANDS.get(owner.command()) != owner) {
            source.sendFailure(Component.literal("未知或不存在的指令"));
            return 0;
        }
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
        payload.addProperty("玩家名", player.getGameProfile().name());
        payload.addProperty("指令", owner.command());
        payload.addProperty("参数", args);
        payload.addProperty("管理员", source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) ? "是" : "否");
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
        // 重编译 / 卸载前先丢掉旧片段：这一步必须放在 NativeBridge 调用**之前**。
        // `[f]初始化` 在 native 里跑，会先编译并实例化新片段；放到后面清理会把刚建好的
        // 实例立刻丢掉，下一次调用又白编译一遍，状态也一起没。
        if ("load".equals(op) || "unload".equals(op)) {
            if (isAllTarget(target)) {
                JavaSnippet.forgetAll();
            } else {
                JavaSnippet.forget(target.trim());
            }
        }
        try {
            raw = switch (op) {
                case "list" -> NativeBridge.list(handle);
                case "load" -> NativeBridge.load(handle, target);
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
        // 失败插件的动作已在 Rust 侧按 `失败插件` 剔除，这里照常落地（`--all` 时成功插件要生效）。
        COMMAND_BATCH.clear();
        executeActions(result, server);
        // 指令 + 生物 / 物品按批次收口：本批没声明的自动下线。
        if (result.has("批次")) {
            commitPluginCommands(server);
            MobRegistry.commit(result.get("批次").getAsLong());
            ItemRegistry.commit(result.get("批次").getAsLong());
        }
        // 卸载不产生注册动作，按插件归属整批注销指令（`--all` 清全部）。
        if ("unload".equals(op)) {
            unregisterPluginCommands(server, target);
        }
        MobSync.markDirty();
        MobSync.sync(server);
        if (!op.equals("list")) {
            EventBridge.refresh(handle);
        }
        logDiagnostics(result);
        String summary = result.has("summary") ? result.get("summary").getAsString() : raw;
        boolean ok = !result.has("ok") || result.get("ok").getAsBoolean();
        if (ok) {
            source.sendSuccess(() -> Component.literal(summary), false);
            return 1;
        }
        source.sendFailure(Component.literal(summary));
        return 0;
    }

    /**
     * 目标是不是「全部」。
     *
     * <p>Rust 侧 {@code is_all} 认 {@code --all / all / * / 全部 / 所有} 五种写法，这里必须
     * 逐一对齐：只判 {@code startsWith("-")} 的话，{@code /nebula unload 全部} 会走成
     * {@code forget("全部")}，一个片段都清不掉。</p>
     */
    private static boolean isAllTarget(String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        String word = target.trim();
        return word.startsWith("-") || word.equals("*") || word.equals("all")
                || word.equals("全部") || word.equals("所有");
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * 打印词库编译诊断（引擎 BuildValue.warnings）。
     *
     * 词库里漏一个结尾 `$` 不会报错、整行只会静默变成文本输出，这类问题以前在
     * 服务端日志里一个字都没有。桥接层把诊断放进 diagnostics 字段，这里统一记
     * WARN，免得 `/nebula load` 的聊天回显被长文本刷屏。
     */
    private static void logDiagnostics(JsonObject result) {
        if (result == null || !result.has("diagnostics") || !result.get("diagnostics").isJsonArray()) {
            return;
        }
        for (JsonElement item : result.getAsJsonArray("diagnostics")) {
            if (item != null && item.isJsonPrimitive()) {
                LOG.warn("[星云] {}", item.getAsString());
            }
        }
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
            try {
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
                            // 插件动作是后台执行，不该把 tp / data merge 的成功回显广播给 OP。
                            server.createCommandSourceStack().withSuppressedOutput(),
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
                    case "弹窗输入" -> {
                        ServerPlayer player = playerOf(server, action);
                        if (player != null) {
                            boolean shown = DialogUi.showInput(player,
                                    action.has("标题") ? action.get("标题").getAsString() : "",
                                    action.has("提示") ? action.get("提示").getAsString() : "",
                                    action.has("标签") ? action.get("标签").getAsString() : "输入",
                                    action.has("最长") ? action.get("最长").getAsInt() : 8,
                                    action.has("提交指令") ? action.get("提交指令").getAsString() : "nkey",
                                    action.has("退出指令") ? action.get("退出指令").getAsString() : "");
                            if (!shown) {
                                LOG.warn("[星云] 弹窗输入未下发：{}", action);
                            }
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
                    // 生物对象：`$怪.生效$` 提交快照 → 登记品种；`$怪.卸载$` 单品种下线。
                    case "注册生物" -> MobRegistry.upsert(action);
                    case "注销生物" -> MobRegistry.remove(
                            action.has("品种") ? action.get("品种").getAsString() : "");
                    // 物品对象：`$剑.生效$` 提交快照 → 登记物品；`$剑.卸载$` 单件下线。
                    case "注册物品" -> ItemRegistry.upsert(action);
                    case "注销物品" -> ItemRegistry.remove(
                            action.has("物品") ? action.get("物品").getAsString() : "");
                    // 映射表里的其余动作统一交给 McActions（命令 / 稳定 API 落地）。
                    default -> McActions.apply(server, action);
                }
            } catch (Throwable t) {
                LOG.error("[星云][动作失败] {}", action, t);
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
        Identifier id = Identifier.tryParse(
                action.has("音效") ? action.get("音效").getAsString() : "");
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) {
            LOG.warn("[星云] 未知音效：{}", id);
            return;
        }
        float volume = action.has("音量") ? (float) action.get("音量").getAsDouble() : 1.0F;
        float pitch = action.has("音调") ? (float) action.get("音调").getAsDouble() : 1.0F;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.MASTER, volume, pitch);
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
        String id = action.get("物品").getAsString();
        int remaining = Math.max(1, action.has("数量") ? action.get("数量").getAsInt() : 1);
        // 注册过的物品按定义构造（名称 / 描述 / 属性 / 食物全都在）；
        // 生物附带的物品（刷怪蛋）归生物插件声明，走标记兜底；都没有才发裸物品。
        ItemStack template = ItemRegistry.stackOf(id, 1);
        if (template == null) {
            JsonObject fromMob = MobRegistry.itemByMarker(id);
            if (fromMob != null) {
                template = ItemRegistry.stack(fromMob, 1);
            }
        }
        if (template == null) {
            Item item = McApi.item(id);
            if (item == Items.AIR) {
                LOG.warn("[星云] 给物品 {}：未注册、也不是原版物品，忽略", id);
                return;
            }
            template = new ItemStack(item);
        }
        int max = Math.max(1, template.getMaxStackSize());
        Inventory inventory = player.getInventory();
        while (remaining > 0) {
            int count = Math.min(remaining, max);
            ItemStack stack = template.copy();
            stack.setCount(count);
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
        takeFrom(McSupport.inventorySlots(inventory), item, remaining);
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
        profile.addProperty("维度", player.level().dimension().identifier().toString());
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
