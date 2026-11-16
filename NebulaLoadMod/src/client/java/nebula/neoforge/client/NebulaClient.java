package nebula.neoforge.client;

import nebula.neoforge.content.NebulaEntities;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端入口：只登记词库插件在用的实体渲染器与自更新检查。
 *
 * <p>方块模型 / 贴图由资源包从各插件 {@code 资源/} 下发。这里不注册键位、不画 GUI、
 * 不发自定义网络包——所有玩法表现都由服务端词库插件决定，客户端只提供「能渲染」的能力。</p>
 *
 * <p>{@code @EventBusSubscriber} 在 21.11 只有 {@code modid} + {@code value}（无 {@code bus}），
 * 默认挂在 MOD 总线上，正好覆盖 {@code EntityRenderersEvent.RegisterRenderers}。</p>
 */
@EventBusSubscriber(modid = "nebula_bridge", value = Dist.CLIENT)
public final class NebulaClient {
    private NebulaClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 每个槽位一个渲染器实例，自带槽位号；外观由 ClientDecls 按槽位 id 查声明。
        for (int i = 0; i < NebulaEntities.SLOT_COUNT; i++) {
            final int slot = i;
            event.registerEntityRenderer(NebulaEntities.SLOTS[i].get(),
                    context -> new DynamicMobRenderer(context, slot));
        }
    }

    private static boolean hadPlayer;
    private static int refreshTicks;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 进服（资源包已加载）时重新读插件的客户端声明，并允许更新检查再跑一次。
        boolean has = Minecraft.getInstance().player != null;
        if (has && !hadPlayer) {
            ClientDecls.refresh();
            ModUpdater.reset();
        }
        hadPlayer = has;
        // 资源包可能晚于进服到达，也可能在运行期被热重推（`/nebula load` 重编译改了生物定义）。
        // 100 tick = 5 秒延迟对热更太慢，缩到 20 tick（1 秒），代价只是读一次资源包索引。
        if (has && ++refreshTicks >= 20) {
            refreshTicks = 0;
            ClientDecls.refresh();
        }
        // 不接管按键、不打开界面；所有表现由插件声明，这里只驱动读取器与更新检查。
        ModUpdater.tick();
        // 自定义皮肤：服务端把 URL 写进 profile 的 textures（占位域名 nebula.invalid），
        // 这里换成当前服务器地址自己下载，再塞回 PlayerInfo 的皮肤 supplier。
        NebulaSkins.tick(Minecraft.getInstance());
    }
}
