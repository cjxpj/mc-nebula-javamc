package nebula.neoforge.content;

import net.minecraft.server.MinecraftServer;
import nebula.neoforge.NebulaPack;

/**
 * 生物对象 → 资源包的同步闸门。
 *
 * <p>词库改一个字段，外观 / 蛋模型 / 创造页条目都会变；这些产物在资源包里，
 * 必须重建 zip 并对在线玩家重推，否则玩家要重进服才看得到。三条编译路径
 * （开服 `loadAll`、`/nebula load|unload`、`debug` 自动重编译）都调这里。</p>
 *
 * <p>{@link #markDirty()} 只是置脏，真正重建在 {@link #sync(MinecraftServer)}；
 * 没有变化时 {@code NebulaPack.rebuildAndRepush} 会比 sha1 直接跳过，不会让全服空重下。</p>
 */
public final class MobSync {
    private static volatile boolean dirty;

    private MobSync() {
    }

    public static void markDirty() {
        dirty = true;
    }

    public static void sync(MinecraftServer server) {
        if (!dirty) {
            return;
        }
        dirty = false;
        NebulaPack.rebuildAndRepush(server);
    }
}
