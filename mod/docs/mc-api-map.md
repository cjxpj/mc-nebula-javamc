# `世界` 内置函数 ↔ NeoForge / Minecraft API 映射

> 本文是**编译期契约**，不是事后补的说明：`bridge/src/lib.rs` 用 `include_str!` 把第 1~6 节的
> 映射表读进单测，双向断言「文档里的方法都有函数、注册过的函数都写进了文档」。新增 / 删除方法
> 的步骤见第 0 节末尾的「维护流程」。设计背景见 [`neoforge.md`](neoforge.md)，
> 三份文档的分工见 [`README.md`](README.md)。

约定：

- 方法名、参数顺序、返回值形态与基岩版 `mc` 保持一致（Java 侧注入对象统一为 `世界`），便于词库迁移。
- 使用 Mojang 官方映射（NeoForge 默认）；类名以 `net.minecraft.*` 为准。
- **状态列**：`确认` = 方向明确，具体签名按目标版本落地；`待版本确认` = 方法存在但签名随版本变动；`需 Mixin` = 无公开事件/API；`不支持` = 明确不做。
- 查询失败返回空串 / `{}` / `[]`；动作失败返回空串并记录日志。
- 表格里的**状态列**是早期设计评估（`确认` / `待版本确认` / `需 Mixin`），不是实现状态；实现情况见下节。原「需 Mixin」的 tick 四项改走 1.20.3 起就有的原生 `/tick`，无需字节码增强。

## 0. 实现状态（2026-11-08 全量落地）

> **注入对象**：词库里统一写 `$世界.取方块 …$`。
> 文件名 `mc-api-map.md` 沿用旧称，避免各处链接失效。

映射表共 **128 个 `世界` 方法全部有对应函数**（22 个手写 hook + 106 个表驱动规格），
词库调用时不会再出现「未知方法」。界面统一用「对象 + 点击闭包」。
统一的规约是「一条映射 = 一个规格 = 一个函数」：

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 注入类 `世界` | `bridge/src/lib.rs` `mc_methods()` + `register_table_driven()` | 规格表把每个方法注册成 `NasFunc`；查询类构造请求 JSON 走 `java_query`，动作类压入动作队列 |
| 查询回调 | `mod/.../McApi.java` | `op` → 读世界 → 返回文本 / JSON 文本 |
| 动作落地 | `mod/.../McActions.java` | `op` → 原版命令或稳定 API；不确定的签名交给反射兜底 |
| 通用支撑 | `mod/.../McSupport.java` | 反射调用、实体查找（id / UUID / 名称）、物品与实体快照、tick 统计、环境变量 |

- 现有的 20 个手写 hook（提示 / 广播 / 界面对象 / 指令注册 / 取消事件 / 取相邻方块 / 取物品数等）保持不动，规格表只补缺失项。
- 绝大多数动作用原版命令实现（`setblock` / `fill` / `fillbiome` / `effect` / `gamemode` / `experience` / `summon` / `particle` / `gamerule` / `save-all` …），好处是自带权限、掉落与客户端同步，也不受版本方法签名浮动影响。
- 少数版本相关查询（种子、生物群系、方块标签、游戏规则、协议版本、实体属性、FoodData setter、经验进度等）走反射，读不到就返回空串 / 默认值，**不会让整个 mod 编译失败**。
- `取TPS` / `取MSPT` / `取服务器tick` 由宿主自己统计 `ServerTickEvent.Pre/Post` 的 40 tick 滑窗，不依赖服务端内部字段。
- 一致性由单测锁住：`cargo test --offline --lib` 里的 `every_mapped_mc_method_has_a_function` 会双向断言「文档每个方法都有函数、每个函数都写进了文档」，另有一条测试校验参数位次与布尔 / 文本 / 回退值。

### 参数规约

规格表按**参数位次**取值，字段命名如下（与宿主 JSON 一致）：

| 词库实参 | 请求字段 | 说明 |
| --- | --- | --- |
| 玩家 | `玩家` | 接受玩家名，也接受 `%玩家%` 注入的 JSON 对象（自动取 `名称`） |
| 维度 | `维度` | `minecraft:overworld` 这类注册名 |
| 坐标 | `x` / `y` / `z` | 区域类用 `x1/y1/z1/x2/y2/z2`；区块类 `x`/`z` 是区块坐标 |
| 实体 | `实体` | 数字 id / UUID / 名称都可以，命令里统一换成 UUID 选择器 |
| 是/否 | `值` | `是 / true / 1 / 开 / 真 / yes / on` 视为真 |
| 数值 | `数值` / `数量` / `时刻` / `秒` / `等级` … | 整型或浮点由规格表指定 |
| 剩余文本 | `文本` / `原因` / `状态` / `数据` / `说明` | 吞掉该位次之后的全部参数，用空格拼接 |

### 可选尾参（省略时用声明的默认值）

**只有尾部参数可以省略**。词库按位置传实参，省掉中间参数会让后面的参数整体错位，所以规格表
只对尾参用 `fd!` 声明默认值。省略时 `build_request` 直接写默认值，而不是让 `d.int()` 的 `0`
混进宿主——否则「秒」会变成 1 秒而不是 30 秒、「数量」会被夹到 1 但语义不清。

| 方法 | 可省参数 | 默认值 |
| --- | --- | --- |
| `给予物品` / `丢弃物品` / `设置副手` | 数量 | 1 |
| `设背包物品` | 数量 | 1 |
| `加效果` | 秒 / 等级 | 30 / 1 |
| `治疗玩家` / `伤害玩家` / `实体伤害` / `实体治疗` | 数值 | 1 |
| `加等级` / `加经验` | 数值 | 1 |
| `设置等级` / `设置经验进度` | 数值 | 0 |
| `设置饥饿` / `设置饱和度` / `设置消耗度` | 数值 | 20 / 5 / 0 |
| `设置权限` | 等级 | 0 |
| `设置能力` / `设置飞行` / `设置无敌` / `设置隐身` / `设置名牌可见` / `实体隐身` | 值 | 是 |
| `取实体列表` | 上限 | 512 |
| `设置区块半径` / `重发区块` | 数值 | 8 / 0 |
| `悬浮字` | 缩放 / 上升 / 时长 | 1 / 1 / 20 |
| `踢出玩家` / `设方块状态` / `生成实体` | 原因 / 状态 / 数据 | 空 |

传全参数的行为完全不变；上面每一项都由 `optional_tail_params_use_defaults` 单测锁住。

### 明确降级 / 占位 / 实现偏差

| 方法 | 现状 |
| --- | --- |
| `打开背包` | **唯一占位**：玩家背包是客户端本地 `InventoryScreen`，服务端不管理；宿主记一条 WARN。需要界面时用 `创建界面` + `打开界面` 打开箱子界面 |
| `生成实体` | 原设计的「整段 SNBT」改为 `维度 类型 X Y Z [NBT]`，走 `summon` 命令；NBT 不能含空格 |
| `设生物群系` | 走 `fillbiome`，Y 范围固定 `-64 ~ 319` |
| `更新天气` | 数值按 `weather rain <秒>` 原样传给命令 |
| `设置权限` | 用 `op` / `deop` 近似「等级 > 0」，不是分级权限 |
| `设置侧边栏` | 用全局 `nebula_sidebar` 目标，标题可重复更新（已存在走 `modify displayname`，不再 remove/add），暂不设置分数行 |
| `交互` | `interactOn` 签名随版本浮动，按参数个数反射调用 |
| `悬浮字` | `text_display` 的 `text` 是 **JSON 字符串**（用 `ComponentSerialization.CODEC` 序列化），不是组件 NBT。写成组件会被静默忽略：实体建得出来，但一个字都没有（`data get … text` 返回 `""`）。宿主先用 `§` 解析器拼样式，再用 `ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, …)` 写入；`brightness` / `transformation` / `billboard` 走 codec，解析失败只记日志不影响实体创建。实体带 `nebula_fx` 标签，宿主每秒扫一次「带标签但不在活动列表」的残留并 discard，开服再扫一遍已加载区块——区块卸载 / 崩服留下的飘字不会永久留在存档里。**平滑要靠 `teleport_duration`（= Java 常量 `TAG_POS_ROT_INTERPOLATION_DURATION`）**：默认 0 时客户端每收到一次位置包就 `applyTargetPosAndRot` + `setOldPosAndRot`，逐 tick 改位置会变成 20 Hz 硬跳；给 2 tick 后走 `lerpPositionAndRotationStep`，渲染按帧过渡 |

> 原先的 7 个占位已消除 6 个：tick 四方法改走 1.20.3 起就有的原生 `/tick`（不需要 Mixin），
> `设置区块半径` / `重发区块` 改走 `ClientboundSetChunkCacheRadiusPacket` /
> `ClientboundLevelChunkWithLightPacket` 两个原生封包。**目前只剩 `打开背包` 一个占位。**

### 1.21.11 真机实测结论

隔离服（独立端口 / RCON，不碰线上 world）跑全量查询与代表性动作后的结论：

| 结论 | 说明 |
| --- | --- |
| 公开 API 直调 | `取种子` / `取协议版本` / `取时间` / `取服务器版本` 直接读 `ServerLevel#getSeed()` / `SharedConstants.getCurrentVersion().protocolVersion()` / `Level#getDayTime()` / `MinecraftServer#getServerVersion()`，不再按名字反射 |
| tick 统计有效 | `取TPS` / `取MSPT` / `取服务器tick` 由 `ServerTickEvent.Pre/Post` 滑窗算出 |
| 动作确实落地 | `设方块`、`设生物群系`、`设时间`、`设难度`、`设游戏规则`、`设复活点`、`生成生物` / `生成实体`、`设环境变量`、`保存世界`、`注册指令` 都能读回或用原版命令反查 |
| 区块未加载 → 查询为空 | 方块类查询先用 `level.isLoaded` 判一次，未加载返回空串 / `{}` / `[]`。`/forceload add 0 0` 之后 `区块已加载=是`，四类方块查询立刻恢复。词库应先 `$世界.区块已加载$` 或用 `/forceload` |
| 规则名按 id 匹配 | 静态常量字段名不稳定，按 `GameRules.Key#getId()` 匹配才能覆盖 `doDaylightCycle` 这类规则 |
| 实体列表要设上限 | 默认最多 512 个实体；`取实体列表 维度 1` 只返回 1 条。整表可能数百字符，而 `say` 有 256 字符上限，长结果必须分批输出或传 `上限` |
| 玩家类动作必须用名字 | `give <uuid>` / `experience set <uuid>` 会报「Only players may be affected…」；`tp` 接受实体所以 UUID 能用，但只允许玩家的命令会拒绝 UUID 字面量。宿主统一用 `GameProfile.name()`；**不能取实体的 `getName()`**，它会被 `设置名牌` 改成显示名 |
| 玩家在线场景全通 | mineflayer 假玩家进服后，11 个玩家查询全部返回真实数据；给 / 扣物品、背包槽位、副手、等级 / 经验、饥饿 / 饱和度、飞行 / 能力、名牌、标签、传送、侧边栏等经读回或 NBT 反查生效 |
| 命令失败现在可见 | 动作命令走 Brigadier `parse + execute`（`performPrefixedCommand` 返回 `void` 且吞异常）。真正的 `CommandSyntaxException` 会打 WARN；「状态没变 / 粒子无人可见 / objective 不存在」统一过滤 |
| 占位只剩 `打开背包` | `/tick` 四方法、`设置区块半径`（`ClientboundSetChunkCacheRadiusPacket`）、`重发区块`（`ClientboundLevelChunkWithLightPacket`）均已原生落地，不需 Mixin；`打开背包` 是客户端本地界面，服务端不接管 |
| 动作效果反查全通 | 12 项原版反查全部 PASS：方块状态 `[axis=x]`、方块数据、填充区域、下界跨维度 `execute in`、生成生物、难度 / 时间 / 效果 / 模式 / 侧边栏 |
| 通用事件桥 | 清单来自 `neoforge-21.11.42-universal.jar`，267 个事件类按订阅动态注册；双假玩家进服触发 `EntityJoinLevelEvent` |
| 一键回归 | `python mod/selftest/regress.py`：隔离服 + 双假玩家 + 原版命令反查 + 悬浮字 / 治疗 / 残留清理 + RCON `stop`，**19/19 PASS、0 告警** |
| 手工复现 | 无玩家场景用 `[f]初始化` / `[f]重载` 跑查询与读回；玩家场景用 mineflayer 假玩家执行 `/自检` 触发 `[指令]` 词条；动作效果用原版 `execute if …` / `data get entity …` 反查 |

词库层的两个坑（不属于 API 映射，但调用时会踩）：

- `%时间%` 是引擎内置的 `Value::Time`（本地时间），`%版本%` 是引擎版本字符串；词库里给同名变量赋值会被系统变量遮蔽，应改用带前缀的名字（如 `%SC时刻%`）。
- 动作是**延迟执行**的：同一词条里 `$世界.设方块 …$` 之后立刻 `$世界.取方块 …$` 读到的仍是旧值，要到下一次事件 / reload 才能读到结果。

### 维护流程：新增 / 删除一个 `世界` 方法

因为本文被编进单测，改方法不是「改完文档再补代码」，而是一次动 4 处，缺一处测试就会红：

1. **本文第 1~6 节的映射表**：增删一行，方法名写在第一列反引号内（`/`、`,`、`，` 分隔的别名会被算成多个方法）。方法名里不要带空格、`[` 或 `$`，这些会被解析器跳过。
2. **规格表**：`bridge/src/lib.rs` 的 `register_table_driven()` 加一条 `q!`（查询）或 `a!`（动作），按本文参数列声明参数位次与类型（`Str` / `Int` / `Float` / `Bool` / `Text` / `Player`）。
3. **宿主分支**：查询加到 `mod/.../McApi.java`，动作加到 `McActions.java`（少数入口在 `NebulaMod.java`）；`op` 名必须与方法名一致。
4. **跑测试**：`cargo test --offline --lib`。三条测试分别锁住文档 ↔ 函数（`every_mapped_mc_method_has_a_function`）、规格表 ↔ 宿主 switch（`every_method_has_a_host_handler`）、参数位次与回退值（`table_driven_request_shape`）。

解析器的边界要记住：只从 `## 1. 方块` 开始读表格，遇到 `## 7.` 停止；只认以 `|` 开头的行，取第一列反引号里的内容；映射区里遇到事件表标题行后就不再收集（那里是事件名，不是方法）。所以**说明性文字一律写在第 0 节**，别塞进映射表格，否则会被误当成新方法。第 0 节里可以放心解释规则——`in_mapping_table` 为真之后才识别事件表标记，说明文字不会误触发。
改降级 / 实测结论、补真机验证记录不影响解析，但改完记得跑一次测试。

## 1. 方块

| `世界` 方法 | 参数 | NeoForge / Minecraft API | 状态 |
| --- | --- | --- | --- |
| `取方块` | 维度 X Y Z | `ServerLevel#getBlockState(BlockPos)` → `BuiltInRegistries.BLOCK.getKey` | 确认 |
| `取方块数据` | 维度 X Y Z | `BlockState#toString()`（含属性文本） | 确认 |
| `获取方块` | 维度 X Y Z | 方块对象：`名称` / `数据` / `标签` / `生物群系` | 确认 |
| `取相邻方块` | 方块数据 / 维度 X Y Z [预算] | 26 邻域 `BlockPos.offset` + `getBlockState`，跳过空气，`ServerLevel#isLoaded` 防加载区块 | 确认 |
| `取方块标签` | 维度 X Y Z | `BlockState#getTags()` → `TagKey#location` | 待版本确认 |
| `设方块` | 维度 X Y Z 方块名 | 原版 `setblock` 命令（Brigadier `parse + execute`） | 确认 |
| `设方块状态` | 维度 X Y Z 名称 [状态] | `BlockStateParser` 解析 `名称[属性=值]` 后 `setBlock` | 待版本确认 |
| `设方块数据` | 维度 X Y Z 数据文本 | 同上，整段 `BlockState` 文本解析 | 待版本确认 |
| `破坏方块` | 维度 X Y Z | `ServerLevel#destroyBlock(BlockPos, drop)` | 确认 |
| `取生物群系` | 维度 X Y Z | `ServerLevel#getBiome(BlockPos)` → `Biome` 注册名 | 确认 |
| `设生物群系` | 维度 X1 Z1 X2 Z2 群系 | `ServerLevel#getChunk` + 区块 biome 写入 | 待版本确认 |
| `填充区域` | 维度 X1 Y1 Z1 X2 Y2 Z2 方块 | 循环 `ServerLevel#setBlock`，限制格数与 `scan_budget` | 确认 |

## 2. 坐标

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `取玩家坐标` | 玩家 | `ServerPlayer#getX/getY/getZ` | 确认 |
| `取复活点坐标` | — | `ServerLevel#getSharedSpawnPos()` | 确认 |
| `设复活点` | X Y Z | `ServerLevel#setDefaultSpawnPos(BlockPos, float)` | 待版本确认 |

## 3. 玩家

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `取玩家状态` | 玩家名 / UUID | `PlayerList#getPlayerByName` / `getPlayer(UUID)` 快照 | 确认 |
| `取玩家列表` | — | `PlayerList#getPlayers()` | 确认 |
| `取玩家数量` | — | `getPlayers().size()` | 确认 |
| `传送玩家` | 玩家 维度 X Y Z | `ServerPlayer#teleportTo(ServerLevel, x, y, z, yaw, pitch)` | 待版本确认 |
| `设置模式` / `取玩家模式` | 模式 玩家 | `ServerPlayer#setGameMode(GameType)` / `getGameMode()` | 确认 |
| `取玩家血量` / `治疗玩家` / `伤害玩家` / `击杀玩家` | 玩家 [数值] | `getHealth` / `heal` / `hurt(server.damageSources().generic(), n)` / `kill` | 确认 |
| `踢出玩家` | 玩家 原因 | `ServerPlayer#connection#disconnect(Component)` | 确认 |
| `扣耐久` | 点数 玩家 | `getMainHandItem()` + `ItemStack#setDamageValue` | 确认 |
| `动作栏` | 玩家 文本 | `ServerPlayer#displayClientMessage(Component, true)` | 确认 |
| `标题` | 玩家 主标题 [副标题] | `ClientboundSetTitleTextPacket` / `ClientboundSetSubtitleTextPacket` | 待版本确认 |
| `设置侧边栏` / `清除侧边栏` | 玩家 文本 | `scoreboard objectives` 命令（只显示标题） | 已落地（命令） |
| `设置等级` / `加等级` / `加经验` / `设置经验进度` | 玩家 数值 | `setExperienceLevels` / `giveExperiencePoints` / `setExperienceProgress` | 确认 |
| `设置饥饿` / `设置饱和度` / `设置消耗度` | 玩家 数值 | `Player#getFoodData()` 的 `setFoodLevel` / `setSaturation`；消耗度写 private 字段 `exhaustionLevel`（公开 API 只有 `addExhaustion`） | 确认（1.21.11 实测） |
| `取玩家权限` / `设置权限` | 玩家 [等级] | `PlayerList#isOp/op/deop(GameProfile)` | 确认 |
| `给予物品` / `丢弃物品` | 玩家 物品 [数量] | `give` 命令 / 背包扣减后 `Player#drop` | 已落地 |
| `取手持物品` / `取副手物品` / `清空背包` / `取装备` | 玩家 | `Inventory` + `McSupport.itemJson` 快照 | 已落地 |
| `取背包物品` | 玩家 槽位 | `Inventory#getItem` | 已落地 |
| `设背包物品` / `设置副手` | 玩家 槽位 物品 数量 / 玩家 物品 数量 | `Inventory#setItem` / `offhand` | 已落地 |
| `加效果` / `移除效果` | 玩家 效果 [秒] [等级] | `BuiltInRegistries.MOB_EFFECT` + `addEffect` / `removeEffect(MobEffectInstance)` | 待版本确认 |
| `设置能力` | 玩家 能力 是\|否 | `Abilities` 字段反射写入 + `onUpdateAbilities` | 已落地 |
| `设置飞行` / `设置无敌` / `设置隐身` | 玩家 是\|否 | `mayfly/flying/invulnerable` / `setInvisible` | 已落地 |
| `设置名牌` / `设置名牌可见` | 玩家 文本/是\|否 | `setCustomName` / `setCustomNameVisible` | 确认 |
| `玩家加标签` / `玩家移除标签` / `玩家有标签` | 玩家 标签 | `Entity#addTag` / `removeTag` / `getTags` | 确认 |
| `设置玩家出生点` | 玩家 维度 X Y Z | `ServerPlayer#setRespawnPosition` | 待版本确认 |
| `设置区块半径` | 玩家 数值 | `ClientboundSetChunkCacheRadiusPacket(int)` | 已落地（封包） |
| `重发区块` | 玩家 [半径] | `ClientboundLevelChunkWithLightPacket` 重发附近已加载区块，半径 0~2 | 已落地（封包） |
| `打开背包` | 玩家 | 客户端本地 `InventoryScreen`，服务端不接管；用 `创建界面` + `打开界面` 替代 | 唯一占位 |
| `播放动作` | 玩家 动作 | `ClientboundAnimatePacket` | 待版本确认 |
| `骑乘` / `取消骑乘` | 玩家 实体id | `startRiding` / `stopRiding` | 确认 |
| `攻击` / `交互` | 玩家 实体id | `ServerPlayer#attack` / `interactOn` | 待版本确认 |

## 4. 世界 / 服务器

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `取时间` / `设时间` | [时刻] | `ServerLevel#getDayTime` / `setDayTime` | 确认 |
| `设天气` / `更新天气` | 天气 / 持续秒数 | `weather` 命令 | 已落地（命令） |
| `取难度` / `设难度` | [难度] | `ServerLevel#getDifficulty` / `MinecraftServer#setDifficulty` | 确认 |
| `取种子` | — | `ServerLevel#getSeed()` | 确认（1.21.11 实测） |
| `取游戏规则` / `设游戏规则` | 名称 [值] | `MinecraftServer#getGameRules()` + 规则键映射 | 待版本确认 |
| `保存世界` | — | `MinecraftServer#saveEverything` / 区块保存 | 待版本确认 |
| `取睡眠状态` | — | `ServerLevel` 睡眠状态 API | 待版本确认 |
| `区块已加载` | 维度 区块X 区块Z | `ServerLevel#isLoaded` / `getChunkSource` | 确认 |
| `取TPS` / `取MSPT` | — | `MinecraftServer#getAverageTickTime` + 桥接层滑动窗口 | 待版本确认 |
| `取服务器tick` | — | `MinecraftServer#getTickCount()` | 确认 |
| `取协议版本` / `取服务器版本` | — | `SharedConstants.getCurrentVersion().protocolVersion()` / `MinecraftServer#getServerVersion()` | 确认（1.21.11 实测） |
| `取环境变量` / `设环境变量` | 名称 [值] | `System#getenv`；设置只影响桥接层自己的调度 | 确认 |
| `取本地时间` | — | `LocalDateTime.now()` | 确认 |
| `设置tick冻结` / `步进tick` / `设置tick加速` / `取模拟暂停` | 是\|否 / 数值 | `/tick freeze\|unfreeze\|step\|rate` 命令 + `ServerTickRateManager#isFrozen()`；1.20.3 起原生支持，无需 Mixin | 已落地 |

## 5. 实体

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `生成生物` | 维度 类型 X Y Z | `EntityType#create(ServerLevel)` + `moveTo` + `addFreshEntity` | 待版本确认 |
| `生成实体` | 维度 类型 X Y Z [NBT] | `summon` 命令 + 可选 NBT（NBT 不含空格） | 已落地（命令） |
| `生成粒子` | 维度 效果 X Y Z | `ServerLevel#sendParticles(ParticleOptions, …)` | 确认 |
| `取实体列表` | [维度] [上限] | `ServerLevel#getAllEntities` / `getEntities`；上限默认 512，最大 512 | 已落地（反射） |
| `取实体坐标` | 实体id | `Entity#getX/getY/getZ` | 确认 |
| `取实体数据` | 实体id | 实体快照 | 确认 |
| `实体传送` | 实体id 维度 X Y Z | `Entity#teleportTo(ServerLevel, …)` | 待版本确认 |
| `实体伤害` / `实体治疗` / `实体击杀` / `实体移除` | 实体id … | `hurt` / `LivingEntity#heal` / `kill` / `discard` | 确认 |
| `实体加标签` / `实体移除标签` / `实体有标签` | 实体id 标签 | `Entity#addTag/removeTag/getTags` | 确认 |
| `取实体属性` | 实体id 属性 | 反射查 `Attributes` 常量 + `getAttributeValue` | 已落地（反射） |
| `实体隐身` | 实体id 是\|否 | `Entity#setInvisible` | 已落地 |
| `悬浮字` | 维度 X Y Z 文本 [缩放] [上升] [时长] | `Display.TextDisplay`：1.21.11 走 `EntityType.create(TagValueInput, Level, EntitySpawnReason.COMMAND)`（键名与 `/summon` 一致，setter 全是 private），宿主逐 tick `setPos` 上浮、到时 `discard` | 已落地 |

## 6. 交互 / 事件

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `提示` | 玩家 文本 | `ServerPlayer#sendSystemMessage(Component)` | 确认 |
| `广播` | 文本 | `PlayerList#broadcastSystemMessage(Component, false)` | 确认 |
| `执行命令` | 命令 | `MinecraftServer#getCommands().performPrefixedCommand(...)` | 确认 |
| `取消事件` | — | 桥接层本次事件槽位 → `event.setCanceled(true)`（通用事件桥里对实现 `ICancellableEvent` 的事件同样生效） | 确认 |

> 事件源不止上表：宿主内置 6 个中文事件、通用事件桥按订阅对接 `nebula/events.json`
> 里的 267 个 NeoForge 事件类（`EventBridge` + `eventTriggers(handle)`）。清单由
> `mod/tools/gen_events.py` 生成并打进 jar，升级 NeoForge 要重新生成 + 全量构建。
> `世界` 方法数量的单测只锁本契约表，不锁事件清单。

## 6.1 指令 / 界面 / 物品

宿主新增的 `世界` 方法（`interface` 前缀的方法由宿主转成事件回传，词库不直接读 Java API）：

| `世界` 方法 | 参数 | 实现 | 状态 |
| --- | --- | --- | --- |
| `取物品数` | 玩家 物品 | 遍历 `Inventory#items / armor / offhand` 的 `ItemStack#getCount`，返回总数 | 确认 |
| `注册指令` | 规范名 [权限] 别名… | `CommandDispatcher#register`；重复注册按插件 + 规范名幂等，权限变化拆旧节点重注册 | 确认 |
| `注销指令` | 规范名 别名… | 从根节点 children 按名字移除，只删该插件自己的入口 | 确认 |
| `创建界面` | 玩家 [行数] 标题… | 返回界面对象 id；同时发 `清空界面` 动作建好容器，Rust 侧记录归属插件 / 玩家 / 标题 | 确认 |
| `界面物品` | 面 槽位 物品 数量 名称 说明 [发光] | 校验对象归属后转成 `设界面物品` 动作（`ItemStack` + `CUSTOM_NAME / LORE / ENCHANTMENT_GLINT_OVERRIDE`） | 确认 |
| `界面数据` | 面 数据… | 校验对象归属后转成 `设界面数据` 动作 | 确认 |
| `打开界面` | 面 回调 | 存下函数值闭包，发 `开界面` 动作（带 `界面ID`）；同一玩家的旧界面对象自动失效 | 确认 |
| `点击消息` | 玩家 段JSON | 一行可点击聊天：段是 `[{文,命令,悬停}]`，宿主给每段挂 `ClickEvent.RunCommand`；点击即以玩家身份执行命令 | 确认（1.21.11 实测） |
| `输入界面` | 玩家 标题 回调 | 铁砧命名框当文本框：存函数值闭包，发 `输入界面` 动作（带 `界面ID`）；输入框初始为空，点成品格时 `clickGui` 回传 `{玩家名,文本,动作}`，其它槽位只读 | 确认（1.21.11 实测） |
| `弹窗输入` | 玩家 标题 提示 标签 最长 提交指令 | 1.21.6+ 原版 dialog：`ClientboundShowDialogPacket(Holder.direct(NoticeDialog))`，一个 `minecraft:text` 输入框 + `dynamic/run_command` 模板 `<提交指令> $(pw)`；点提交由客户端发出 `<提交指令> <输入值>`，无需数据包 / `/reload` | 1.21.11 落地 |
| `关闭界面` | 面 | 发 `关界面` 动作并释放闭包 | 确认 |
| `标题` | 玩家 主标题 [副标题] | `ClientboundSetTitlesAnimationPacket` + `SetTitleTextPacket` + `SetSubtitleTextPacket` | 确认 |
| `音效` | 玩家 音效 [音量] [音调] | `ServerPlayer#playNotifySound(SoundEvent, SoundSource, float, float)` | 确认 |
| `给物品` | 玩家 物品 [数量] | `Inventory#add`，按最大堆叠分批，塞不下 `Player#drop` | 确认 |
| `扣物品` | 玩家 物品 [数量] | 遍历背包 / 副手 / 盔甲扣减，不足时能扣多少扣多少 | 确认 |

指令与点击事件：

| 事件 | payload 字段 | 触发方式 | 状态 |
| --- | --- | --- | --- |
| `[指令]商店\|shop` | `玩家 / 玩家名 / 指令 / 参数 / 管理员` | 指令执行时按规范名匹配 `dic_funcs["指令"]` 的别名列表，只跑命中词条（`dispatchCommand`） | 确认 |
| 界面对象点击闭包 | `玩家 / 玩家名 / 界面 / 槽位 / 动作 / 按钮 / 区域 / 物品 / 数量 / 数据` 组成 JSON，作为闭包的 `%参数1%` | `NebulaMenu#clicked` 拦截点击后 `clickGui(handle, 界面ID, payload)`，Rust 直接调该对象保存的函数值；不移动任何物品 | 确认 |

## 7. 明确缺口

| 能力 | 现状 | 建议 |
| --- | --- | --- |
| 物品栏点击事件 | 已用自定义 `ChestMenu#clicked` 拦截，按界面对象 id 调点击闭包；只覆盖词库打开的箱子界面，原版容器 / 玩家背包界面不接管 | 需要监听任意容器时再考虑 Mixin `AbstractContainerMenu#clicked` |
| tick 冻结 / 步进 / 加速 | 1.20.3 起有原生 `/tick`，1.21.11 已用命令 + `ServerTickRateManager` 落地；真机验证 frozen / rate 40 / unfreeze 都生效 | 无需 Mixin |
| 客户端本地背包界面 | 只有 `打开背包` 仍是占位（服务端不管理 `InventoryScreen`）；`设置区块半径` / `重发区块` 已用原生封包落地 | 用 `创建界面` + `打开界面` 打开箱子界面 |
| 客户端侧动作（粒子 / 标题 / 音效）执行端 | 服务端发命令 / 发包 | 统一走服务端，不需要客户端 mod |
| SNBT 实体生成 | 原版 SNBT 解析器不公开；改为 `summon 类型 坐标 [NBT]`，NBT 不含空格 | 需要完整 SNBT 时再引入内部解析器 |
| 动态枚举类 API（游戏规则 / 属性注册表 / 事件 getter） | 需要遍历注册表或按字段名派生，仍走反射；种子 / 协议号 / 时间 / FoodData / 实体交互已改直调公开 API | 升级 MC 版本后跑一次查询冒烟 |
| 睡眠状态 / 侧边栏分数行 | 睡眠状态按反射结果返回是否；侧边栏只显示标题 | 需要分数行时扩展 `设置侧边栏` 的参数 |
