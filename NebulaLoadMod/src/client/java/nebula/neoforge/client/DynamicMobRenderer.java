package nebula.neoforge.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import nebula.neoforge.content.DynamicMob;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 通用「外观方块」渲染器：每个槽位一个实例，画哪个方块 / 偏移 / 阴影全部来自
 * 插件 {@code 资源/客户端/*.json} 的 {@code 实体外观} 声明（由 {@code NebulaPack} 下发）。
 *
 * <p>Key 用<b>槽位实体 id</b>（{@code nebula_bridge:mob_7}），不是品种 id：品种 id 是服务端
 * NBT，客户端实体上拿不到；槽位是两端一致的注册项，天然可作为索引。</p>
 *
 * <p><b>没有声明时什么都不画</b> —— mod 不内置任何外观。</p>
 *
 * <p>两个动作都是纯表现，不影响碰撞箱 / 交互：</p>
 * <ul>
 *   <li><b>走路弹跳</b>：{@code walkAnimation} 的相位过 {@code |sin|}，每一步弹一次，
 *       幅度跟行走速度成正比（站着不动 speed→0，高度自然归零），跳起时轻微前倾；</li>
 *   <li><b>开箱</b>：盖子（{@code 盖子} 指向的另一只外观方块）绕后上边缘旋转
 *       {@code open × 100°}，{@code open} 由 {@link DynamicMob#openAmount} 按帧插值给出。</li>
 * </ul>
 */
public final class DynamicMobRenderer extends EntityRenderer<DynamicMob, DynamicMobRenderer.State> {
    /** 弹跳高度的缺省值（格）：0.14 ≈ 2.2 像素，够显眼又不至于像在飞。词库可覆盖。 */
    private static final float DEFAULT_HOP_HEIGHT = 0.14F;
    /** 跳起时前倾的最大角度缺省值；和 hop 同源，落地时自然归零。 */
    private static final float DEFAULT_HOP_TILT = 80.0F;
    /** 全开时盖子转过的角度缺省值：100° 略过垂直，像真箱子那样「向后仰倒」。 */
    private static final float DEFAULT_LID_ANGLE = 100.0F;
    /**
     * 铰链缺省位置：后上边缘（方块坐标 0..1，贴图 16 格里 y=14）。
     *
     * <p>词库用 {@code $怪.造型 盖 …$} 改过盖子盒子后，服务端会按新盒子下发 {@code 铰链}，
     * 这里就吃声明；没声明时退回这两个老缺省，像素级保持原观感。</p>
     */
    private static final float HINGE_Y = 14.0F / 16.0F;
    private static final float HINGE_Z = 1.0F;

    private final String entityId;
    private final String lidFallback;

    public DynamicMobRenderer(EntityRendererProvider.Context context, int slot) {
        super(context);
        this.entityId = "nebula_bridge:mob_" + slot;
        this.lidFallback = "nebula_bridge:mob_lid_" + slot;
        JsonObject look = ClientDecls.entityLook(entityId);
        this.shadowRadius = look == null ? 0F : (float) ClientDecls.num(look, "阴影", 0.45);
    }

    /** 每帧重建的表现状态；实体字段在这里被「抄」过来，submit 只读它。 */
    public static final class State extends EntityRenderState {
        public float walkPosition;
        public float walkSpeed;
        public float open;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DynamicMob entity, State state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.walkPosition = entity.walkAnimation.position(partialTick);
        state.walkSpeed = entity.walkAnimation.speed(partialTick);
        state.open = entity.openAmount(partialTick);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        JsonObject look = ClientDecls.entityLook(entityId);
        if (look == null) {
            return;
        }
        Block body = block(ClientDecls.str(look, "方块", ""));
        if (body == null) {
            return;
        }
        Block lid = block(ClientDecls.str(look, "盖子", lidFallback));

        // 四个「整只怪」的表现量：都由 `$怪.动画 键 值$` 声明，缺省为「不起作用」。
        float hopHeight = (float) ClientDecls.num(look, "弹跳", DEFAULT_HOP_HEIGHT);
        float hopTilt = (float) ClientDecls.num(look, "前倾", DEFAULT_HOP_TILT);
        float lidAngle = (float) ClientDecls.num(look, "开盖", DEFAULT_LID_ANGLE);
        float sway = (float) ClientDecls.num(look, "摇摆", 0);
        float spin = (float) ClientDecls.num(look, "旋转", 0);
        float scale = (float) ClientDecls.num(look, "缩放", 1);
        float hover = (float) ClientDecls.num(look, "浮空", 0);

        // |sin(相位)| 每走一步弹一次；乘速度让「刚起步 / 快停下」自然收小。
        float gait = Mth.clamp(state.walkSpeed, 0.0F, 1.0F);
        float hop = Mth.abs(Mth.sin(state.walkPosition)) * hopHeight * gait;
        double[] off = ClientDecls.offset(look);

        BlockState bodyState = body.defaultBlockState();
        BlockState lidState = lid == null ? null : lid.defaultBlockState();

        // 共享变换层：位置（偏移 + 弹跳 + 浮空）→ 前倾 → 摇摆 → 自转 → 呼吸缩放。
        // 体与盖都挂在这一层下，所以盖子天然继承全部整体动画，只需再叠一次铰链旋转。
        pose.pushPose();
        pose.translate((float) off[0], (float) off[1] + hop + hover, (float) off[2]);
        if (hop > 1.0E-4F) {
            // 绕实体中心轻微前倾（局部 -Z 是正面，负角=向前点头）
            pose.mulPose(Axis.XP.rotationDegrees(-hop * hopTilt));
        }
        if (sway != 0.0F) {
            // 走路左右摇：相位跟步态，站着不动（gait→0）自然归零，不会原地扭。
            pose.mulPose(Axis.YP.rotationDegrees(sway * Mth.sin(state.walkPosition) * gait));
        }
        if (spin != 0.0F) {
            // 自转：单位是「每秒多少度」，按实体年龄换算，和服务器重启的 tick 无关。
            pose.mulPose(Axis.YP.rotationDegrees(spin * state.ageInTicks / 20.0F));
        }
        if (Math.abs(scale - 1.0F) > 1.0E-4F) {
            // 呼吸缩放：绕方块中心脉动，振幅 = |缩放-1|，一个来回约 2 秒。
            float pulse = 1.0F + (scale - 1.0F) * (0.5F + 0.5F * Mth.sin(state.ageInTicks * 0.15F));
            pose.translate(0.5F, 0.5F, 0.5F);
            pose.scale(pulse, pulse, pulse);
            pose.translate(-0.5F, -0.5F, -0.5F);
        }
        collector.submitBlock(pose, bodyState,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);

        if (lidState != null) {
            // 盖子：先把坐标平移到铰链，转，再转回来。两次提交各自会 copy 一份 pose，
            // 所以这里怎么摆都不会串到箱体上。
            double[] hinge = ClientDecls.pair(look, "铰链", HINGE_Y, HINGE_Z);
            float hingeY = (float) hinge[0];
            float hingeZ = (float) hinge[1];
            pose.pushPose();
            pose.translate(0.5F, hingeY, hingeZ);
            pose.mulPose(Axis.XP.rotationDegrees(state.open * lidAngle));
            pose.translate(-0.5F, -hingeY, -hingeZ);
            collector.submitBlock(pose, lidState,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
            pose.popPose();
        }
        pose.popPose();
    }

    /** 声明里的方块 id → 已注册方块；写错 / 没装那个 mod 时返回 null（不画，别崩）。 */
    private static Block block(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return BuiltInRegistries.BLOCK.get(Identifier.parse(id))
                    .map(holder -> holder.value()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
