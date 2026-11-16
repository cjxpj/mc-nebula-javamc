package nebula.neoforge.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端自定义皮肤 / 披风。
 *
 * <p>服务端把自定义贴图的 URL 写进 profile 的 {@code textures}（{@code SKIN} 与 {@code CAPE}
 * 两个节点），域名用占位符 {@code nebula.invalid}。原版客户端只认
 * {@code textures.minecraft.net}，会在 {@code unpackTextures} 阶段把整份属性丢掉；本类不碰那条路，
 * 而是自己解析 base64 里的 URL，换成「当前服务器地址 + 内置 HTTP 端口」，走原版的
 * {@code SkinTextureDownloader}（负责尺寸校验、legacy 转换、本地缓存），
 * 再把拼好的 {@link PlayerSkin} 塞回 {@link PlayerInfo#getSkin()} 用的 {@code skinLookup} 字段。</p>
 *
 * <p>只处理 {@code http://nebula.invalid/nebula/skins/} 与官方
 * {@code https://textures.minecraft.net/} 两种前缀：前者要换成服务器地址，后者是「自定义披风
 * 配正版皮肤」时的那半张皮肤，直接下。其它 URL 一概不碰，所以不会变成任意 URL 加载器。</p>
 *
 * <p>原版客户端看不到自定义贴图（会显示默认皮肤 / 无披风），正版账号的签名贴图不受影响。</p>
 */
public final class NebulaSkins {
    private static final Logger LOG = LogManager.getLogger("nebula");
    private static final String PLACEHOLDER = "http://nebula.invalid";
    private static final String SKIN_PATH = "/nebula/skins/";
    private static final String MOJANG_TEXTURES = "https://textures.minecraft.net/";
    private static final int DEFAULT_PORT = 25568;

    /** 已注入的皮肤：uuid → 记录（同一个 PlayerInfo 被服务端重建后要重新注入）。 */
    private static final Map<UUID, Applied> APPLIED = new ConcurrentHashMap<>();
    /** 已下载的贴图：真实 URL → 纹理。皮肤与披风共用一张表。 */
    private static final Map<String, ClientAsset.Texture> TEXTURES = new ConcurrentHashMap<>();
    /** 正在下载的 URL，避免每 tick 重复提交。 */
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private static Field skinLookupField;
    private static Field playerInfoField;
    private static Field downloaderField;
    private static boolean reflectFailed;

    /** key 是「皮肤 URL|披风 URL」；info 是注入时的 PlayerInfo 实例。 */
    private record Applied(String key, String raw, PlayerInfo info, Supplier<PlayerSkin> origin) {
    }

    /** 从 textures 里挑出来的、需要自己托管的地址；两个都为空表示交给原版。 */
    private record Wanted(String skinUrl, String capeUrl, PlayerModelType model) {
    }

    private NebulaSkins() {
    }

    static void tick(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            APPLIED.clear();
            return;
        }
        rebind(minecraft);
        String base = serverBase(minecraft);
        if (base.isEmpty()) {
            return;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            try {
                apply(minecraft, base, info);
            } catch (Throwable t) {
                if (!reflectFailed) {
                    reflectFailed = true;
                    LOG.warn("[星云] 自定义皮肤注入失败：{}", t.toString());
                }
            }
        }
    }

    private static void apply(Minecraft minecraft, String base, PlayerInfo info) {
        GameProfile profile = info.getProfile();
        UUID id = profile.id();
        Property property = firstTexture(profile);

        // 服务端重建 PlayerInfo 后是新对象：旧注入记录作废，按新属性重新判断。
        Applied previous = APPLIED.get(id);
        if (previous != null && previous.info() != info) {
            APPLIED.remove(id);
            previous = null;
        }

        String raw = property == null ? "" : property.value();
        // 同一份 textures 不用每 tick 重新 base64 解码；服务端换肤时属性会变。
        if (previous != null && previous.raw().equals(raw)) {
            return;
        }
        Wanted wanted = wantedOf(raw);
        if (wanted == null) {
            // 已经切回原版（清除 / 纯正版）：把注入的 supplier 还原，避免残留。
            if (previous != null) {
                setSkinLookup(info, previous.origin());
                APPLIED.remove(id);
            }
            return;
        }
        String skinTarget = target(base, wanted.skinUrl());
        String capeTarget = target(base, wanted.capeUrl());
        String key = skinTarget + "|" + capeTarget;
        if (previous != null && previous.key().equals(key)) {
            return;
        }
        ClientAsset.Texture skin = skinTarget.isEmpty() ? null : TEXTURES.get(skinTarget);
        ClientAsset.Texture cape = capeTarget.isEmpty() ? null : TEXTURES.get(capeTarget);
        boolean waiting = false;
        if (!skinTarget.isEmpty() && skin == null) {
            // 皮肤要过 legacy 处理：64×32 的老皮肤得先升级成 64×64
            download(minecraft, id, skinTarget, true);
            waiting = true;
        }
        if (!capeTarget.isEmpty() && cape == null) {
            // 披风绝不能过 legacy 处理：它本身就是 64×32，被当皮肤升级就整块拉花
            download(minecraft, id, capeTarget, false);
            waiting = true;
        }
        if (waiting) {
            return;
        }
        Supplier<PlayerSkin> origin = previous == null ? currentLookup(info) : previous.origin();
        PlayerSkin fallback = origin == null ? null : origin.get();
        ClientAsset.Texture body = skin != null ? skin : fallback == null ? null : fallback.body();
        if (body == null) {
            return;
        }
        ClientAsset.Texture capeTexture = cape != null ? cape : fallback == null ? null : fallback.cape();
        PlayerModelType model = wanted.model() != null ? wanted.model()
                : fallback != null ? fallback.model() : PlayerModelType.WIDE;
        PlayerSkin composed = new PlayerSkin(body, capeTexture,
                fallback == null ? null : fallback.elytra(), model, true);
        if (!setSkinLookup(info, () -> composed)) {
            return;
        }
        APPLIED.put(id, new Applied(key, raw, info, origin));
    }

    /**
     * 把实体上缓存的 {@code PlayerInfo} 换成玩家列表里的最新实例。
     *
     * <p>{@code AbstractClientPlayer.playerInfo} 是「懒加载一次就永不更新」：
     * 第一次 {@code getPlayerInfo()} 取到对象后存进字段，之后既不查表也不看属性。
     * 服务端 Remove + Add 只会在 {@code playerInfoMap} 里新建一个 PlayerInfo，
     * 实体仍指着旧对象（旧 profile、旧的 memoized skinLookup），皮肤自然一直是旧的。
     * 原版从不处理「在线玩家中途换皮肤」，所以没有对应的失效逻辑；
     * 只有实体重建（重进服务器 / 换维度 / 走远再回来）才会重新绑定。</p>
     */
    private static void rebind(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        ClientPacketListener connection = minecraft.getConnection();
        if (level == null || connection == null) {
            return;
        }
        for (Player player : level.players()) {
            if (!(player instanceof AbstractClientPlayer avatar)) {
                continue;
            }
            try {
                PlayerInfo current = connection.getPlayerInfo(avatar.getUUID());
                if (current != null && playerInfoField().get(avatar) != current) {
                    playerInfoField().set(avatar, current);
                }
            } catch (Throwable t) {
                if (!reflectFailed) {
                    reflectFailed = true;
                    LOG.warn("[星云] 自定义皮肤注入失败：{}", t.toString());
                }
            }
        }
    }

    private static Field playerInfoField() throws NoSuchFieldException {
        if (playerInfoField == null) {
            Field field = AbstractClientPlayer.class.getDeclaredField("playerInfo");
            field.setAccessible(true);
            playerInfoField = field;
        }
        return playerInfoField;
    }

    /** 触发懒初始化后读出现有的 supplier，用来在切回原版时还原。 */
    @SuppressWarnings("unchecked")
    private static Supplier<PlayerSkin> currentLookup(PlayerInfo info) {
        try {
            info.getSkin();
            Field field = skinLookupField();
            Object value = field.get(info);
            return value instanceof Supplier<?> supplier ? (Supplier<PlayerSkin>) supplier : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setSkinLookup(PlayerInfo info, Supplier<PlayerSkin> supplier) {
        if (supplier == null) {
            return false;
        }
        try {
            skinLookupField().set(info, supplier);
            return true;
        } catch (Throwable t) {
            if (!reflectFailed) {
                reflectFailed = true;
                LOG.warn("[星云] 自定义皮肤注入失败：{}", t.toString());
            }
            return false;
        }
    }

    private static Field skinLookupField() throws NoSuchFieldException {
        if (skinLookupField == null) {
            Field field = PlayerInfo.class.getDeclaredField("skinLookup");
            field.setAccessible(true);
            skinLookupField = field;
        }
        return skinLookupField;
    }

    private static void download(Minecraft minecraft, UUID id, String url, boolean legacy) {
        if (!LOADING.add(url)) {
            return;
        }
        try {
            Object downloader = downloader(minecraft);
            if (downloader == null) {
                LOADING.remove(url);
                return;
            }
            String hash = hashOf(url);
            Identifier textureId = Identifier.tryParse("nebula:skins/"
                    + id.toString().replace("-", "") + "_" + hash);
            if (textureId == null) {
                LOADING.remove(url);
                return;
            }
            Path cache = FMLPaths.GAMEDIR.get().resolve("nebula-skins")
                    .resolve(id + "-" + hash + ".png");
            Method method = downloader.getClass().getMethod("downloadAndRegisterSkin",
                    Identifier.class, Path.class, String.class, boolean.class);
            @SuppressWarnings("unchecked")
            CompletableFuture<ClientAsset.Texture> future =
                    (CompletableFuture<ClientAsset.Texture>) method.invoke(
                            downloader, textureId, cache, url, legacy);
            future.thenAccept(texture -> minecraft.execute(() -> {
                TEXTURES.put(url, texture);
                LOADING.remove(url);
            })).exceptionally(error -> {
                LOADING.remove(url);
                LOG.debug("[星云] 自定义贴图下载失败 {}：{}", url, error.toString());
                return null;
            });
        } catch (Throwable t) {
            LOADING.remove(url);
            if (!reflectFailed) {
                reflectFailed = true;
                LOG.warn("[星云] 皮肤下载器不可用：{}", t.toString());
            }
        }
    }

    private static Object downloader(Minecraft minecraft) {
        try {
            if (downloaderField == null) {
                Field field = minecraft.getSkinManager().getClass()
                        .getDeclaredField("skinTextureDownloader");
                field.setAccessible(true);
                downloaderField = field;
            }
            return downloaderField.get(minecraft.getSkinManager());
        } catch (Throwable t) {
            return null;
        }
    }

    // ────────────────────────────── 解析 ──────────────────────────────

    private static Property firstTexture(GameProfile profile) {
        try {
            var found = profile.properties().get("textures");
            return found.isEmpty() ? null : found.iterator().next();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析 textures，挑出需要本地托管的地址；没有（纯正版 / 空）返回 null。 */
    private static Wanted wantedOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("textures") || !root.get("textures").isJsonObject()) {
                return null;
            }
            JsonObject textures = root.getAsJsonObject("textures");
            String skin = urlOf(textures, "SKIN");
            String cape = urlOf(textures, "CAPE");
            if (!isCustom(skin) && !isCustom(cape)) {
                return null;
            }
            return new Wanted(allowed(skin) ? skin : null, allowed(cape) ? cape : null, modelOf(textures));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String urlOf(JsonObject textures, String kind) {
        if (!textures.has(kind) || !textures.get(kind).isJsonObject()) {
            return null;
        }
        JsonObject node = textures.getAsJsonObject(kind);
        String url = node.has("url") ? node.get("url").getAsString() : "";
        return url.isEmpty() ? null : url;
    }

    private static boolean isCustom(String url) {
        return url != null && url.startsWith(PLACEHOLDER + SKIN_PATH);
    }

    /** 只认自家占位域名与 Mojang 贴图 CDN，别的一律不下。 */
    private static boolean allowed(String url) {
        return isCustom(url) || (url != null && url.startsWith(MOJANG_TEXTURES));
    }

    private static PlayerModelType modelOf(JsonObject textures) {
        try {
            if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                return null;
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                JsonObject metadata = skin.getAsJsonObject("metadata");
                String model = metadata.has("model") ? metadata.get("model").getAsString() : "";
                return "slim".equalsIgnoreCase(model) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            }
            return PlayerModelType.WIDE;
        } catch (Throwable t) {
            return PlayerModelType.WIDE;
        }
    }

    /** 占位地址换成「当前进服地址 + HTTP 端口」；Mojang 地址原样返回。 */
    private static String target(String base, String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith(PLACEHOLDER + SKIN_PATH)) {
            return base + url.substring(PLACEHOLDER.length());
        }
        return url;
    }

    /** 服务端写的是 {@code 192.168.x.x} 这种自己看到的地址；玩家用哪个地址进的服就用哪个。 */
    private static String serverBase(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            return "";
        }
        String host = hostOf(server.ip);
        if (host.isEmpty()) {
            return "";
        }
        int port = DEFAULT_PORT;
        try {
            JsonObject decl = ClientDecls.all().has("外观") && ClientDecls.all().get("外观").isJsonObject()
                    ? ClientDecls.all().getAsJsonObject("外观") : null;
            if (decl != null) {
                port = (int) ClientDecls.num(decl, "端口", DEFAULT_PORT);
            }
        } catch (Throwable ignored) {
            // 声明没到 / 坏了就用默认端口
        }
        return "http://" + host + ":" + port;
    }

    private static String hostOf(String ip) {
        if (ip == null) {
            return "";
        }
        String text = ip.trim();
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            return close > 0 ? text.substring(0, close + 1) : text;
        }
        int first = text.indexOf(':');
        int last = text.lastIndexOf(':');
        if (first > 0 && first != last) {
            // 没带方括号的裸 IPv6，原样加括号返回
            return "[" + text + "]";
        }
        return first > 0 ? text.substring(0, first) : text;
    }

    private static String hashOf(String url) {
        int index = url.indexOf("?v=");
        String hash = index >= 0 ? url.substring(index + 3) : "";
        if (hash.isEmpty()) {
            hash = Integer.toHexString(url.hashCode());
        }
        return hash.replaceAll("[^0-9a-zA-Z]", "");
    }
}
