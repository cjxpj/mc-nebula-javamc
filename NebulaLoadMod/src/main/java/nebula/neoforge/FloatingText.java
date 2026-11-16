package nebula.neoforge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * 词库驱动的悬浮字（RPG 飘字）。
 *
 * <p>用原版 {@code minecraft:text_display} 实体实现，不依赖客户端 mod。实体在宿主里配置：
 * 1.21.11 的 {@code Display} / {@code TextDisplay} setter 仍是 private，只能按 NBT 走
 * {@code EntityType.create(CompoundTag, Level)}——即 {@code /summon} 的同一条解析路径，
 * 键名与召唤语法完全一致（{@code text} / {@code billboard} / {@code see_through} /
 * {@code background} / {@code transformation} …）。其中 {@code text} 必须是组件 NBT
 * （{@code ComponentSerialization.CODEC} + {@code NbtOps}），不是 1.21.1 时代的 JSON 字符串。</p>
 *
 * <p>上浮是宿主逐 tick {@code setPos}：位移小，客户端按实体插值平滑渲染。到时直接
 * {@code discard()}，不留尸体。</p>
 *
 * <p><b>残留兜底</b>：TextDisplay 和普通实体一样会随区块存盘。飘字只在 1 秒内可见，但三种情况
 * 会把它留在存档里——崩服 / 强杀时正在显示、显示期间所在区块被卸载（玩家传送走、区块卸载）、
 * 卸载后又重新加载（这时存档里会冒出**另一个**实体对象，我们手里那个已经作废）。所以实体带
 * {@value #FX_TAG} 标签，并且每秒扫一次「带标签但不在活动列表里」的飘字直接 {@code discard}：
 * 区块一被加载就会被清掉，不会永久留在世界里。开服时还会额外扫一遍已加载区块。</p>
 */
final class FloatingText {
    private static final Logger LOG = LogManager.getLogger("nebula");
    /** 残留清理用的实体标签。 */
    private static final String FX_TAG = "nebula_fx";
    /** 默认上浮高度（格）。 */
    private static final double DEFAULT_RISE = 1.0;
    /** 默认存活 tick 数。 */
    private static final int DEFAULT_LIFE = 20;
    /** 残留扫描间隔（tick）。1 秒扫一次，飘字最长也就 2 秒。 */
    private static final int SWEEP_INTERVAL = 20;
    /** 同时存在的飘字上限。横扫 / 刷怪场一次打十几个目标时不至于刷出几百个实体。 */
    private static final int MAX_ACTIVE = 64;

    private static final List<Pop> ACTIVE = new ArrayList<>();
    private static boolean warned = false;
    /** 距离下一次残留扫描的 tick 计数。 */
    private static int sweepTicks = 0;

    private FloatingText() {
    }

    /** 一个正在上浮的飘字。位置在生成时算好，之后只改 y。 */
    private static final class Pop {
        final Entity entity;
        final double x;
        final double baseY;
        final double z;
        final double rise;
        final int life;
        int age;

        Pop(Entity entity, double x, double baseY, double z, double rise, int life) {
            this.entity = entity;
            this.x = x;
            this.baseY = baseY;
            this.z = z;
            this.rise = rise;
            this.life = life;
        }
    }

    /**
     * {@code 悬浮字 维度 X Y Z 文本 [缩放] [上升] [时长]}。
     *
     * <p>文本里的 {@code §} 颜色码在宿主侧解析成组件样式——TextDisplay 是实体元数据，
     * 客户端不会像聊天栏那样自己认 {@code §}。</p>
     */
    static void spawn(MinecraftServer server, JsonObject action) {
        ServerLevel level = McSupport.level(server, McSupport.str(action, "维度"));
        if (level == null) {
            return;
        }
        String text = McSupport.str(action, "文本");
        if (text.isEmpty()) {
            return;
        }
        double x = McSupport.number(action, "x", 0.0);
        double y = McSupport.number(action, "y", 0.0);
        double z = McSupport.number(action, "z", 0.0);
        float scale = (float) McSupport.number(action, "缩放", 1.0);
        if (scale <= 0.0F) {
            scale = 1.0F;
        }
        double rise = McSupport.number(action, "上升", DEFAULT_RISE);
        if (rise <= 0.0) {
            rise = DEFAULT_RISE;
        }
        int life = McSupport.integer(action, "时长", DEFAULT_LIFE);
        if (life <= 0) {
            life = DEFAULT_LIFE;
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        // 1.21.11 的 TextDisplay.readAdditionalSaveData 走
        // ValueInput.read("text", ComponentSerialization.CODEC)：text 是 **组件 NBT**。
        // 1.21.1 那套 "JSON 字符串 + Component.Serializer.fromJson" 已不存在；仍 putString
        // 塞 JSON 会被 codec 当成纯文本组件，客户端把整串 '{"text":…}' 原样画出来
        // （data get … text 返回带引号的字符串，而不是结构化组件）。
        Tag textTag = ComponentSerialization.CODEC
                .encodeStart(NbtOps.INSTANCE, legacy(text))
                .result().orElse(null);
        if (textTag == null) {
            warnOnce("悬浮字文本无法序列化为组件 NBT：" + text);
            return;
        }
        tag.put("text", textTag);
        // 朝向摄像机、穿墙可见、不要默认黑底，避免盖住场景。
        tag.putString("billboard", "center");
        tag.putBoolean("see_through", true);
        tag.putBoolean("shadow", false);
        tag.putBoolean("default_background", false);
        tag.putInt("background", 0);
        tag.putInt("line_width", 1000);
        // 关键：Display 默认 teleport_duration = 0，客户端每收到一次位置包就
        // `applyTargetPosAndRot` + `setOldPosAndRot` —— 硬跳且不留渲染插值，
        // 我们又是逐 tick 改位置（20 次/秒），看起来就是一跳一跳的。
        // 给 2 tick 插值后走 `lerpPositionAndRotationStep`，渲染按帧平滑过渡。
        // 注意 NBT 键叫 teleport_duration（Java 常量名是 TAG_POS_ROT_INTERPOLATION_DURATION）。
        tag.putInt("teleport_duration", 2);
        // 满亮度：夜里 / 洞里也看得清数字，否则红字会跟着环境变暗。
        tag.put("brightness", brightness());
        tag.put("transformation", transformation(scale));
        tag.put("Tags", fxTags());

        Optional<Entity> created = EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level, EntitySpawnReason.COMMAND);
        if (created.isEmpty()) {
            warnOnce("悬浮字实体创建失败（text_display NBT 未被接受）");
            return;
        }
        Entity display = created.get();
        display.setPos(x, y, z);
        if (!level.addFreshEntity(display)) {
            return;
        }
        // 到上限先顶掉最老的那个：宁可少飘一排，也不要让实体和封包无限堆。
        while (ACTIVE.size() >= MAX_ACTIVE) {
            ACTIVE.remove(0).entity.discard();
        }
        ACTIVE.add(new Pop(display, x, y, z, rise, life));
    }

    /** 由 {@link NebulaMod#onTickPost} 每 tick 调用：推进上浮动画，顺手定期扫残留。 */
    static void tick(MinecraftServer server) {
        animate();
        if (++sweepTicks >= SWEEP_INTERVAL) {
            sweepTicks = 0;
            sweepStrays(server);
        }
    }

    private static void animate() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Iterator<Pop> it = ACTIVE.iterator(); it.hasNext();) {
            Pop pop = it.next();
            if (pop.entity.isRemoved() || pop.age >= pop.life) {
                pop.entity.discard();
                it.remove();
                continue;
            }
            pop.age++;
            double progress = (double) pop.age / pop.life;
            // 先快后慢：起跳明显，收尾停住，比匀速更像 RPG 飘字。
            double eased = 1.0 - (1.0 - progress) * (1.0 - progress);
            pop.entity.setPos(pop.x, pop.baseY + pop.rise * eased, pop.z);
        }
    }

    /**
     * 清掉「带 {@value #FX_TAG} 标签、但不在活动列表里」的飘字。
     *
     * <p>这是兜底而不是主路径：正常飘字到期就 {@code discard} 了，不会走到这里。命中的只有
     * 存档里翻出来的残留（崩服时正在显示、区块卸载后又加载）。实体在未加载区块里时
     * {@code getAllEntities} 看不到，所以清理发生在**区块加载之后**，最多晚 1 秒——
     * 视觉上就是数字多停了一拍。</p>
     */
    private static void sweepStrays(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            // 没人在的维度不清：既没人看得见，也省掉一次全量遍历；玩家进维度后会立刻补上。
            if (level.players().isEmpty()) {
                continue;
            }
            purgeLevel(level);
        }
    }

    private static void purgeLevel(ServerLevel level) {
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getType() != EntityType.TEXT_DISPLAY) {
                continue;
            }
            if (entity.getTags().contains(FX_TAG) && !isLive(entity)) {
                stale.add(entity);
            }
        }
        for (Entity entity : stale) {
            entity.discard();
        }
        if (!stale.isEmpty()) {
            LOG.info("[星云] 清理了 {} 个残留悬浮字（{}）", stale.size(),
                    level.dimension().identifier());
        }
    }

    /** 活动列表里的飘字按引用比较：区块重载出来的新对象不算同一个。 */
    private static boolean isLive(Entity entity) {
        for (Pop pop : ACTIVE) {
            if (pop.entity == entity) {
                return true;
            }
        }
        return false;
    }

    /** 关服前全部收掉，免得带着飘字存盘。 */
    static void clear() {
        for (Pop pop : ACTIVE) {
            pop.entity.discard();
        }
        ACTIVE.clear();
    }

    /** 开服清理上次崩溃 / 强杀留下的飘字实体（只覆盖已加载区块，其余的由每秒兜底扫描接手）。 */
    static void sweep(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            purgeLevel(level);
        }
    }

    // ────────────────────────── NBT 组装 ──────────────────────────

    /** {@code transformation} 只用到缩放：四个字段都写全，避免 codec 认为缺项。 */
    private static CompoundTag transformation(float scale) {
        CompoundTag out = new CompoundTag();
        out.put("translation", floats(0.0F, 0.0F, 0.0F));
        out.put("left_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        out.put("right_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        out.put("scale", floats(scale, scale, scale));
        return out;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    private static ListTag fxTags() {
        ListTag list = new ListTag();
        list.add(StringTag.valueOf(FX_TAG));
        return list;
    }

    /** {@code Brightness.CODEC} 的字段是 `block` / `sky`，取值 0~15；两个都拉满。 */
    private static CompoundTag brightness() {
        CompoundTag out = new CompoundTag();
        out.putInt("block", 15);
        out.putInt("sky", 15);
        return out;
    }

    /**
     * 把 {@code §} 颜色码解析成组件样式。
     *
     * <p>聊天栏的 {@code Component.literal("§c红")} 依赖客户端渲染时再解释颜色码，而 TextDisplay
     * 走的是实体元数据里的结构化组件，颜色码留在字符串里会原样显示成 {@code §c}。这里自己拆成
     * 带 {@link Style} 的片段，保证飘字在任何客户端上都是对的颜色。</p>
     */
    static Component legacy(String raw) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '§' && i + 1 < raw.length()) {
                ChatFormatting format = ChatFormatting.getByCode(raw.charAt(i + 1));
                if (format != null) {
                    if (!buffer.isEmpty()) {
                        out.append(Component.literal(buffer.toString()).withStyle(style));
                        buffer.setLength(0);
                    }
                    if (format == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (format.isFormat()) {
                        style = style.applyFormat(format);
                    } else {
                        style = Style.EMPTY.applyFormat(format);
                    }
                    i++;
                    continue;
                }
            }
            buffer.append(ch);
        }
        if (!buffer.isEmpty()) {
            out.append(Component.literal(buffer.toString()).withStyle(style));
        }
        return out;
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            LOG.warn("[星云] {}", message);
        }
    }
}
