package nebula.neoforge.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * 词库声明的通用生物。槽位池里的每个 {@code EntityType} 都指向这个类，
 * 「品种」由 {@code $世界.注册生物$} 在运行期绑定。
 *
 * <p>这是原 {@code ChestCreature} 的泛化版本：容器格数、属性、外观都不再写死，
 * 全部来自 {@link MobRegistry} 的品种定义。读档时存档里的 {@code 品种} 可能
 * 尚未注册（服务端注册表早于 {@code ServerStartedEvent}，而词库在之后才加载），
 * 所以属性 / 格数在第一次 {@link #tick()} 时补应用一次，而不是构造时。</p>
 *
 * <p>本类<b>不注册任何 MOVE 目标</b>：走路归词库 {@code $世界.寻路$}，留着
 * {@code RandomStrollGoal} 会跟它抢同一条腿。</p>
 */
public final class DynamicMob extends PathfinderMob implements Container, MenuProvider {
    /**
     * 箱盖开合进度 0..1，服务端算好同步给客户端。
     *
     * <p>为什么不只同步「有没有人开着」那个布尔：布尔只能在两个 tick 之间瞬变，
     * 客户端渲染就得自己做缓动 —— 但 render state 每帧重建、存不住历史，
     * 只能靠实体字段，那不如把缓动放在服务端做一次，两边都读到同一条曲线。</p>
     */
    private static final EntityDataAccessor<Float> DATA_OPEN =
            SynchedEntityData.defineId(DynamicMob.class, EntityDataSerializers.FLOAT);

    /** 品种 id；未绑定时为空串。 */
    private String species = "";
    /** 容器格数；0 表示不是容器。 */
    private int slotCount;
    private NonNullList<ItemStack> items = NonNullList.withSize(0, ItemStack.EMPTY);
    /** 属性只在首次 tick 应用一次，避免每 tick 覆盖。 */
    private boolean applied;
    /** 服务端：当前有几名玩家开着这只箱子（{@code ChestMenu} 构造 / removed 成对调用）。 */
    private int openCount;
    /** 每 tick 的开合步进；词库 {@code $怪.动画 开合 x$} 按品种覆盖。 */
    private float openStep = 0.18F;
    /** 交互距离（格）；词库 {@code $怪.设 容器距离 n$} 按品种覆盖，默认 8。 */
    private double interactRange = 8.0;
    /**
     * 声明过的碰撞箱；{@code null} 表示用槽位注册时的体型档尺寸。
     *
     * <p>{@code EntityType} 的尺寸是编译期注册的，运行期改不了 —— 但实体可以覆写
     * {@link #getDimensions(Pose)} 把声明值顶上去，碰撞箱、眼睛高度、寻路体积就都跟着走。</p>
     */
    private EntityDimensions declaredDimensions;
    /** 上次「右键给予」的 tick；10 tick 冷却，防连点刷物品。 */
    private int lastGiftTick = -100;
    /** 词库 `$怪.目标$` 装配出来的 Goal；重载声明时先摘掉再重加，不会越堆越多。 */
    private final List<Goal> declaredGoals = new ArrayList<>();
    private final List<Goal> declaredTargets = new ArrayList<>();
    /** 两端：上一 tick / 当前 tick 的开合进度，渲染按 partialTick 在两者间插值。 */
    private float openOld;
    private float open;

    public DynamicMob(EntityType<? extends DynamicMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OPEN, 0.0F);
    }

    /** 渲染用：按帧插值的开合进度（0=闭合，1=全开）。 */
    public float openAmount(float partialTick) {
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), openOld, open);
    }

    public String species() {
        return species;
    }

    /** 绑定品种并立即应用定义（生成路径用）。 */
    public void bind(String species) {
        if (species == null || species.isBlank()) {
            return;
        }
        this.species = species;
        applyDefinition();
    }

    /** 从 {@link MobRegistry} 取定义，覆盖属性、容器格数、持久化。 */
    private void applyDefinition() {
        MobRegistry.MobDef def = MobRegistry.byId(species);
        if (def == null) {
            return;
        }
        applied = true;
        openStep = def.openStep();
        interactRange = def.containerRange();
        if (def.persistent()) {
            setPersistenceRequired();
        }
        setAttributeBase(Attributes.MAX_HEALTH, def.health());
        setAttributeBase(Attributes.MOVEMENT_SPEED, def.speed());
        setAttributeBase(Attributes.JUMP_STRENGTH, def.jump());
        setAttributeBase(Attributes.KNOCKBACK_RESISTANCE, def.knockback());
        if (getHealth() > getMaxHealth()) {
            setHealth(getMaxHealth());
        }
        if (slotCount != def.slots()) {
            int old = slotCount;
            slotCount = def.slots();
            NonNullList<ItemStack> fresh = NonNullList.withSize(slotCount, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(old, slotCount) && i < items.size(); i++) {
                fresh.set(i, items.get(i));
            }
            items = fresh;
        }
        // 碰撞箱：`$怪.设 碰撞箱 …$` 手写宽高，或写「自动」按造型盒子并集算。
        double[] size = def.collisionSize();
        declaredDimensions = size == null
                ? null
                : EntityDimensions.scalable((float) size[0], (float) size[1]);
        if (this.level() != null) {
            refreshDimensions();
        }
        rebuildGoals();
    }

    /**
     * 碰撞箱 / 眼睛高度 / 寻路体积的唯一入口。
     *
     * <p>{@code LivingEntity#getDimensions} 是 {@code final}（它只是
     * {@code getDefaultDimensions(pose).scale(getScale())}），真正可覆写的钩子是
     * {@code getDefaultDimensions}。没声明时走 {@code EntityType} 的注册尺寸（体型档），
     * 声明了就整只换掉 —— 造型改小、碰撞箱跟着小，不用重编 jar、也不用换客户端。</p>
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return declaredDimensions != null ? declaredDimensions : super.getDefaultDimensions(pose);
    }

    private void setAttributeBase(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                  double value) {
        var instance = getAttribute(attribute);
        if (instance != null && value > 0) {
            instance.setBaseValue(value);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.22)
                // 必须显式加跳跃力：createLivingAttributes 里没有 JUMP_STRENGTH，
                // 缺了它寻路算得出路、腿却跳不上一格台阶。
                .add(Attributes.JUMP_STRENGTH, 0.42)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.4);
    }

    @Override
    protected void registerGoals() {
        // 只留「不下达移动指令」的两个。注视玩家由 `$怪.目标 注视 …$` 决定（见 rebuildGoals），
        // 免得写死的 LookAtPlayerGoal 用掉 LOOK flag、把声明的那条挤掉。
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ────────────────────────── 词库声明的 AI 目标 ──────────────────────────

    /**
     * 按 {@code $怪.目标 …$} 重建 AI：先摘掉上次声明的 Goal，再按新声明装一遍。
     *
     * <p><b>只做参数装配，不写新 AI</b> —— 用的都是原版 Goal（{@link MeleeAttackGoal} 等），
     * 路径、视线、攻击冷却全跟原版一致，也就不会出现「模组的怪行为很怪」。</p>
     *
     * <p>没声明任何「注视」时补一条缺省 LookAtPlayerGoal（8 格看玩家），
     * 保持「怪会看着你」的老观感。</p>
     *
     * <p>⚠️ 这些 Goal 会自己 {@code moveTo}，和词库 {@code $世界.巡逻$} 抢同一条腿。
     * 声明了攻击 / 逃跑 / 恐慌 的品种就别再用巡逻 —— 两边互相顶掉目标，表现为原地抽搐。</p>
     */
    private void rebuildGoals() {
        for (Goal goal : declaredGoals) {
            this.goalSelector.removeGoal(goal);
        }
        declaredGoals.clear();
        for (Goal goal : declaredTargets) {
            this.targetSelector.removeGoal(goal);
        }
        declaredTargets.clear();

        boolean hasLook = false;
        MobRegistry.MobDef def = MobRegistry.byId(species);
        if (def != null && def.goals() != null) {
            for (JsonElement element : def.goals()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject spec = element.getAsJsonObject();
                String kind = text(spec, "类型", "");
                String target = text(spec, "对象", "玩家");
                double first = number(spec, "数值1", 0);
                double second = number(spec, "数值2", 0);
                Class<? extends LivingEntity> type = entityClass(target);
                switch (kind) {
                    case "注视" -> {
                        if (type != null) {
                            addGoal(1, new LookAtPlayerGoal(this, type, (float) (first > 0 ? first : 8)));
                            hasLook = true;
                        }
                    }
                    case "攻击" -> {
                        if (type != null) {
                            double speed = first > 0 ? first : 1.0;
                            addTargetGoal(0, new NearestAttackableTargetGoal(this, type, true));
                            addGoal(1, new MeleeAttackGoal(this, speed, true));
                        }
                    }
                    case "逃跑" -> {
                        if (type != null) {
                            double distance = first > 0 ? first : 8;
                            double speed = second > 0 ? second : 1.4;
                            addGoal(2, new AvoidEntityGoal(this, type, (float) distance, speed, speed));
                        }
                    }
                    case "恐慌" -> addGoal(1, new PanicGoal(this, first > 0 ? first : 1.2));
                    case "漂浮" -> {
                        // FloatGoal 在 registerGoals 里常驻，重复加只是浪费 tick。
                    }
                    default -> {
                    }
                }
            }
        }
        if (!hasLook) {
            addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        }
    }

    private void addGoal(int priority, Goal goal) {
        this.goalSelector.addGoal(priority, goal);
        declaredGoals.add(goal);
    }

    private void addTargetGoal(int priority, Goal goal) {
        this.targetSelector.addGoal(priority, goal);
        declaredTargets.add(goal);
    }

    /** `$怪.目标$` 的「对象」→ 实体类；写错返回 {@code null}（跳过这条声明）。 */
    private static Class<? extends LivingEntity> entityClass(String name) {
        return switch (name) {
            case "玩家", "player" -> Player.class;
            case "怪物", "monster" -> Monster.class;
            case "动物", "animal" -> Animal.class;
            default -> null;
        };
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double number(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 读档时 MobRegistry 还没就绪（词库在 ServerStartedEvent 之后加载），
        // 这里补一次；之后 applied=true 不再进入。
        if (!applied && !species.isEmpty() && MobRegistry.ready()) {
            applyDefinition();
        }
        openOld = open;
        if (this.level().isClientSide()) {
            // 客户端只跟服务端给的进度走：openCount 在客户端永远是 0（startOpen 只在服务端被调）。
            open = entityData.get(DATA_OPEN);
        } else {
            open = Mth.approach(open, openCount > 0 ? 1.0F : 0.0F, openStep);
            entityData.set(DATA_OPEN, open);
        }
    }

    // ────────────────────────── 箱盖开合 ──────────────────────────
    //
    // 钩子成对来自 ChestMenu：构造器里调 startOpen(ContainerUser)，
    // AbstractContainerMenu#removed → ChestMenu#removed 调 stopOpen(ContainerUser)。
    // 玩家走远、掉线、死亡都会走 removed，所以计数会自己归零。

    @Override
    public void startOpen(ContainerUser user) {
        openCount++;
    }

    @Override
    public void stopOpen(ContainerUser user) {
        openCount = Math.max(0, openCount - 1);
    }

    // ────────────────────────── 右键开箱 ──────────────────────────

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        MobRegistry.MobDef def = MobRegistry.byId(species);
        String kind = def == null ? "开容器" : def.interactKind();
        switch (kind) {
            case "无" -> {
                // 表里没动作：返回 PASS，让 `[事件]右键实体` 去处理，别挡住别的插件。
                return super.mobInteract(player, hand);
            }
            case "给予" -> {
                if (this.level().isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                // 10 tick 冷却：原版右键能连点，没冷却就是刷物品机。
                if (this.tickCount - lastGiftTick < 10) {
                    return InteractionResult.SUCCESS;
                }
                ItemStack gift = giftStack(def);
                if (gift.isEmpty()) {
                    return InteractionResult.PASS;
                }
                lastGiftTick = this.tickCount;
                if (!player.addItem(gift) && this.level() instanceof ServerLevel server) {
                    this.spawnAtLocation(server, gift, 0.5F);
                }
                return InteractionResult.SUCCESS;
            }
            default -> {
                if (slotCount > 0 && !this.level().isClientSide()) {
                    player.openMenu(this);
                    return InteractionResult.SUCCESS;
                }
                return super.mobInteract(player, hand);
            }
        }
    }

    /** `$怪.交互 给予 物品 数量$` 声明的那堆东西；id 写错就返回空，右键等于没反应。 */
    private ItemStack giftStack(MobRegistry.MobDef def) {
        String id = def == null ? "" : def.interactItem();
        if (id.isBlank()) {
            return ItemStack.EMPTY;
        }
        int count = def.interactCount();
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(id))
                    .map(holder -> new ItemStack(holder.value(), count))
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public Component getDisplayName() {
        // 客户端拿不到服务端的 `species` 字段，退回槽位注册名（lang 里两个键都生成了）。
        String key = species.isBlank()
                ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(getType()).getPath()
                : species.replace(':', '_');
        return Component.translatable("entity.nebula_bridge." + key);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        MenuType<?> type = switch (rows()) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
        return new ChestMenu(type, id, inventory, this, rows());
    }

    private int rows() {
        return Math.max(1, Math.min(6, (slotCount + 8) / 9));
    }

    public int collect(ItemEntity drop) {
        return Containers.absorb(this, this, drop);
    }

    // ────────────────────────── 死亡掉落 ──────────────────────────

    @Override
    public void die(DamageSource source) {
        super.die(source);
        MobRegistry.MobDef def = MobRegistry.byId(species);
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        // 「死亡掉落」只管容器内容；`$怪.掉落$` 声明的是额外的固定掉落，两者互不影响。
        if (def == null || def.dropOnDeath()) {
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                this.spawnAtLocation(server, stack, 0.5F);
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (def == null || def.drops() == null) {
            return;
        }
        for (JsonElement element : def.drops()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "物品", "");
            double chance = Math.min(1.0, Math.max(0.0, number(entry, "概率", 1.0)));
            if (id.isBlank() || this.getRandom().nextDouble() > chance) {
                continue;
            }
            int count = (int) Math.max(1, Math.min(64, number(entry, "数量", 1)));
            ItemStack stack = stackOf(id, count);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(server, stack, 0.5F);
            }
        }
    }

    /** 物品 id + 数量 → ItemStack；id 写错返回空（不掉，也不崩）。 */
    private static ItemStack stackOf(String id, int count) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(id))
                    .map(holder -> new ItemStack(holder.value(), count))
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    // ────────────────────────── 存储 ──────────────────────────

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString("NebulaSpecies", species);
        out.putInt("NebulaSlots", slotCount);
        ContainerHelper.saveAllItems(out, items);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        species = in.getStringOr("NebulaSpecies", "");
        slotCount = in.getIntOr("NebulaSlots", 0);
        items = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        applied = false;
    }

    // ────────────────────────── Container ──────────────────────────

    @Override
    public int getContainerSize() {
        return slotCount;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack taken = ContainerHelper.removeItem(items, slot, count);
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        // 实体容器没有方块更新，数据在 addAdditionalSaveData 里落盘。
    }

    @Override
    public boolean stillValid(Player player) {
        // 距离来自品种声明（`$怪.设 容器距离 n$`），不再写死 8 格。
        return !isRemoved() && player.distanceToSqr(this) <= interactRange * interactRange;
    }

    @Override
    public void clearContent() {
        items.clear();
    }
}
