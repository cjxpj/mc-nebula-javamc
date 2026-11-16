package nebula.neoforge.content;

import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 通用「外观方块」池：每个生物槽位两个，只给实体渲染器画模型用。
 *
 * <ul>
 *   <li>{@code mob_body_<槽位>} —— 主体；</li>
 *   <li>{@code mob_lid_<槽位>} —— 盖子，渲染器单独提交一次才能绕铰链转（开箱动画）。</li>
 * </ul>
 *
 * <p>不放置、不掉落、不阻挡、无碰撞。外观走方块模型（blockstate + 模型 JSON + 贴图），
 * 全部是<b>数据</b>，由 {@link MobRegistry#entries()} 派生、随资源包下发，
 * 换外观不用重编 jar。</p>
 *
 * <p>为什么盖子必须是<b>独立方块</b>而不是同一个模型里的第二个 element：方块模型是一次性
 * 提交的整体几何（{@code submitBlock}），没法只旋转其中一个 element。拆成两个方块状态，
 * 渲染器就能给盖子单独摆 pose。</p>
 */
public final class NebulaBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("nebula_bridge");

    @SuppressWarnings("unchecked")
    public static final DeferredBlock<Block>[] MOB_BODIES = new DeferredBlock[NebulaEntities.SLOT_COUNT];

    @SuppressWarnings("unchecked")
    public static final DeferredBlock<Block>[] MOB_LIDS = new DeferredBlock[NebulaEntities.SLOT_COUNT];

    static {
        for (int i = 0; i < NebulaEntities.SLOT_COUNT; i++) {
            final int index = i;
            MOB_BODIES[i] = BLOCKS.registerBlock(
                    "mob_body_" + index,
                    Block::new,
                    props -> props
                            .noCollision()
                            .noOcclusion()
                            .instabreak()
                            .noLootTable());
            MOB_LIDS[i] = BLOCKS.registerBlock(
                    "mob_lid_" + index,
                    Block::new,
                    props -> props
                            .noCollision()
                            .noOcclusion()
                            .instabreak()
                            .noLootTable());
        }
    }

    private NebulaBlocks() {
    }
}
