package nebula.neoforge.client;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 客户端侧读创造页声明。
 *
 * <p>为什么不在客户端读 {@code <gameDir>/nebula/<插件>/创造物品.json}：那个目录只在<b>服务端</b>存在
 * （玩家机器上没有），而创造页是客户端自己重建的
 * （{@code CreativeModeInventoryScreen#tryRebuildTabContents} → {@code CreativeModeTab#buildContents}
 * → {@code EventHooks.onCreativeModeTabBuildContents}），所以声明必须走「能到客户端」的通道。</p>
 *
 * <p>{@code NebulaPack} 打包时会把每个插件的 {@code 创造物品.json} 复制成
 * {@code assets/nebula/creative/<序号>_<插件名>.json}，这里从资源包把它们全读出来。
 * 因此后台改声明 = 改插件目录 + 重启服务端（资源包 sha 变了，客户端会重新下载）。</p>
 */
public final class ClientCreativeDecls {
    private ClientCreativeDecls() {
    }

    /** 返回资源包里所有创造页声明的原文；读不到就返回空表（不抛异常，别拖垮创造页）。 */
    public static List<String> load() {
        List<String> out = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return out;
        }
        ResourceManager resources = minecraft.getResourceManager();
        if (resources == null) {
            return out;
        }
        var found = resources.listResources("creative",
                id -> "nebula".equals(id.getNamespace()) && id.getPath().endsWith(".json"));
        for (var entry : found.entrySet()) {
            try {
                Resource resource = entry.getValue();
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = resource.openAsReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line).append('\n');
                    }
                }
                if (!text.isEmpty()) {
                    out.add(text.toString());
                }
            } catch (Exception ignored) {
                // 单个文件坏了就跳过，剩下的照常加
            }
        }
        return out;
    }
}
