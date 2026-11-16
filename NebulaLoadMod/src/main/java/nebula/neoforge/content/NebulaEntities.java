package nebula.neoforge.content;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 通用生物槽位池。
 *
 * <p>MC 的 {@code EntityType} 是两端握手时比对的静态契约，词库不可能在运行期新增注册项
 * （服务端单方面加，客户端会走 {@code neoforge.network.registries.sync.server-with-unknown-keys}
 * 断开）。所以这里在编译期一次性注册一批通用槽位，全部指向 {@link DynamicMob}；
 * 运行期词库 {@code $世界.注册生物$} 认领一个槽位、绑定品种数据 —— <b>动态的是数据，不是注册项</b>。</p>
 *
 * <p>碰撞箱 / 眼高 / {@code MobCategory} 是 {@code EntityType.Builder} 的编译期常量，
 * 运行期改不了，所以槽位按体型档分组：{@code tiny / small / normal / large} 各 8 个。
 * 品种的属性（血量 / 速度 / 跳跃 / 击退）在 spawn 后由 {@code DynamicMob} 覆盖。</p>
 */
public final class NebulaEntities {
    public static final DeferredRegister.Entities ENTITY_TYPES =
            DeferredRegister.createEntities("nebula_bridge");

    /** 体型档，顺序必须与 {@link #SLOT_ARCHETYPE} 对齐。 */
    public static final String[] ARCHETYPES = {"tiny", "small", "normal", "large"};
    /** 每档槽位数。 */
    public static final int PER_ARCHETYPE = 8;
    public static final int SLOT_COUNT = ARCHETYPES.length * PER_ARCHETYPE;

    /** 槽位 → 体型档下标。 */
    public static final int[] SLOT_ARCHETYPE = new int[SLOT_COUNT];

    @SuppressWarnings("unchecked")
    public static final DeferredHolder<EntityType<?>, EntityType<DynamicMob>>[] SLOTS =
            new DeferredHolder[SLOT_COUNT];

    static {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int archetype = i / PER_ARCHETYPE;
            SLOT_ARCHETYPE[i] = archetype;
            float[] size = sizeOf(ARCHETYPES[archetype]);
            final int index = i;
            SLOTS[i] = ENTITY_TYPES.registerEntityType(
                    "mob_" + index,
                    DynamicMob::new,
                    MobCategory.CREATURE,
                    builder -> builder.sized(size[0], size[1]).eyeHeight(size[1] * 0.85f));
        }
    }

    private static float[] sizeOf(String archetype) {
        return switch (archetype) {
            case "tiny" -> new float[]{0.5f, 0.5f};
            case "small" -> new float[]{0.9f, 0.9f};
            case "large" -> new float[]{1.5f, 2.0f};
            default -> new float[]{0.6f, 1.8f};
        };
    }

    private NebulaEntities() {
    }

    /** MOD 总线监听：所有槽位共用同一份默认属性，品种差异在 spawn 后覆盖。 */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            event.put(SLOTS[i].get(), DynamicMob.createAttributes().build());
        }
    }
}
