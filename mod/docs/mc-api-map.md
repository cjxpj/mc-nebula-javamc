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
- 表格里的**状态列**是早期设计评估（`确认` / `待版本确认` / `需 Mixin`），不是实现状态；实现情况见下节。原「需 Mixin」的 tick 四项后来发现 1.21.1 有原生 `/tick`，无需字节码增强。

## 0. 实现状态（2026-11-08 全量落地）

> **注入对象**：词库里统一写 `$世界.取方块 …$`。
> 文件名 `mc-api-map.md` 沿用旧称，避免各处链接失效。

映射表共 **127 个 `世界` 方法全部有对应函数**（22 个手写 hook + 105 个表驱动规格），
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
| `悬浮字` | `text_display` 的 `text` 在 1.21.1 是 **JSON 字符串**（`contains("text", 8)` + `Component.Serializer.fromJson`），不是组件 NBT。写成组件会被静默忽略：实体建得出来，但一个字都没有（`data get … text` 返回 `""`）。宿主先用 `§` 解析器拼样式，再 `Component.Serializer.toJson` 写入；`brightness` / `transformation` / `billboard` 走 codec，解析失败只记日志不影响实体创建。实体带 `nebula_fx` 标签，宿主每秒扫一次「带标签但不在活动列表」的残留并 discard，开服再扫一遍已加载区块——区块卸载 / 崩服留下的飘字不会永久留在存档里。**平滑要靠 `teleport_duration`（= Java 常量 `TAG_POS_ROT_INTERPOLATION_DURATION`）**：默认 0 时客户端每收到一次位置包就 `applyTargetPosAndRot` + `setOldPosAndRot`，逐 tick 改位置会变成 20 Hz 硬跳；给 2 tick 后走 `lerpPositionAndRotationStep`，渲染按帧过渡 |

> 原先的 7 个占位已消除 6 个：tick 四方法改走 1.20.3 起就有的原生 `/tick`（不需要 Mixin），
> `设置区块半径` / `重发区块` 改走 `ClientboundSetChunkCacheRadiusPacket` /
> `ClientboundLevelChunkWithLightPacket` 两个原生封包。**目前只剩 `打开背包` 一个占位。**

### 1.21.1 / NeoForge 21.1.265 真机实测（2026-11-08）

独立测试服（隔离端口 25566 / RCON，不碰线上 world）跑全量查询与代表性动作后的结论：

| 结论 | 说明 |
| --- | --- |
| 反射路径全通 | `取种子`（12345）、`取协议版本`（767）、`取服务器版本`（1.21.1）、`取游戏规则`（doFireTick=true / doDaylightCycle=false）、`取方块标签`（实测 4 个标签）、`取生物群系`（ocean → plains）、`取方块数据`（`Block{minecraft:diamond_block}`）都在本版本读到正确值 |
| tick 统计有效 | `取TPS≈19.99` / `取MSPT≈0.17` / `取服务器tick≈2517`，由 `ServerTickEvent.Pre/Post` 滑窗算出 |
| 动作确实落地 | `设方块`（读回 diamond_block）、`设生物群系`（fillbiome 后群系变 plains）、`设时间 12345`、`设难度 普通`、`设游戏规则 doDaylightCycle false`、`设复活点`（读回 0,100,0）、`生成生物`/`生成实体`（原版 `execute if entity` 反查到猪 ×5 / 牛 ×1）、`设环境变量`（读回 yes）、`保存世界`、`注册指令`；`fillbiome` 命令在本版本存在 |
| 区块未加载 → 查询为空 | 方块类查询先用 `level.isLoaded` 判一次，未加载返回空串 / `{}` / `[]`。实测 `/forceload add 0 0` 之后 `区块已加载=是`，四类方块查询立刻恢复。词库应先 `$世界.区块已加载$` 或用 `/forceload` |
| 规则名按 id 匹配 | 静态常量字段名不随版本稳定，曾导致 `doDaylightCycle` 查不到；现按 `GameRules.Key#getId()` 匹配，两种写法都返回正确值 |
| 实体列表要设上限 | 默认最多 512 个实体；`取实体列表 维度 1` 实测只返回 1 条。整表可能数百字符，而 `say` 有 256 字符 chat 上限，长结果必须分批输出或传 `上限` |
| 玩家类动作必须用名字 | 1.21.1 实测 `give <uuid>` / `experience set <uuid>` 会报「Only players may be affected…」：`tp` 接受实体所以 UUID 能用，但只允许玩家的命令会拒绝 UUID 字面量。宿主统一用 `GameProfile.getName()`；**不能取 `getName()`**，它会被 `设置名牌` 改成显示名 |
| 玩家在线场景全通 | mineflayer 假玩家进服后，11 个玩家查询全部返回真实数据；`给物品` / `给予物品` / `扣物品` / `设背包物品` / `设置副手` / `设置等级` / `加等级` / `加经验` / `设置经验进度` / `设置饥饿` / `设置饱和度` / `设置飞行` / `设置能力` / `设置名牌` / `玩家加标签` / `传送玩家` / `设置侧边栏` 等经读回或 NBT 反查生效；`打开背包` / `设置区块半径` / `重发区块` 三个占位按预期只记 WARN |
| 命令失败现在可见 | 宿主改走 Brigadier `parse + execute`（`performPrefixedCommand` 在 1.21.1 返回 `void` 且把异常吞进 `sendFailure`，叠加 `withSuppressedOutput` 后完全静默）。真正的 `CommandSyntaxException` 会打 WARN；`gamemode` 模式没变、`deop` 非 OP 这类「无变化」不记，避免噪音 |
| 占位从 7 降到 1 | 真机验证：`/tick query` 显示 `The game is frozen / Target tick rate: 40.0`，`取模拟暂停` 在 freeze / unfreeze 后分别读回 是 / 否，`步进tick` 无异常；玩家在线时 `设置区块半径`（`ClientboundSetChunkCacheRadiusPacket`）与 `重发区块`（`ClientboundLevelChunkWithLightPacket`）都不抛异常。仅 `打开背包` 仍是占位 |
| 动作效果反查全通 | 12 项原版反查全部 PASS：`设方块`(diamond_block)、`设方块状态`(`oak_log[axis=x]`)、`设方块数据`(`stone[]`)、`填充区域`(gold_block)、下界 `设方块`(glowstone)、`生成生物`(pig×2)、`设难度`(Hard)、`设时间`(6000+)、`加效果`(speed amplifier=1)、`设置模式`(创造)、`设置侧边栏`(标题可更新)；`execute in minecraft:the_nether` 跨维度路径生效 |
| 多玩家 / 跨维度 | 两个假玩家在线时 `取玩家列表` 返回 2 条、`取玩家数量=2`；`传送玩家` 到 `minecraft:the_nether` 后 `取玩家状态.维度` 变成 the_nether |
| 告警只留真错误 | 原版把「状态没变 / 粒子无人可见 / objective 不存在」也做成 `CommandSyntaxException`，宿主已过滤；`设置侧边栏` 改用 Scoreboard API 判断，不再发注定失败的 `remove`。真失败（目标不存在、区块未加载、UUID 被拒绝）仍打 WARN |
| 一键回归 | `python mod/selftest/regress.py`（`--keep` 保留现场）：自动准备隔离服（端口 25566 / RCON 25576，目录联接共享 `libraries`/`.jdk`，独立 `level-name`）、装 mineflayer、启动两个假玩家、跑 `selftest/wordlib` 词库、按 `selftest/checks.json` 批量反查、恢复 tick、RCON `stop` 并清理。实测 18/18 PASS、0 告警 |
| 手工复现 | 无玩家场景用 `[f]初始化` / `[f]重载` 跑查询与读回；玩家场景用 mineflayer 假玩家执行 `/自检` 触发 `[指令]` 词条；动作效果用原版 `execute if …` / `data get entity …` 反查。RCON `/nebula reload <插件>` 触发第二轮 |

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
| `设方块` | 维度 X Y Z 方块名 | `BuiltInRegistries.BLOCK.get(ResourceLocation)` + `BlockState#defaultBlockState` + `ServerLevel#setBlock` | 确认 |
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
| `设置饥饿` / `设置饱和度` / `设置消耗度` | 玩家 数值 | `Player#getFoodData()` 对应 setter | 确认 |
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
| `取种子` | — | `ServerLevel#getSeed` | 待版本确认 |
| `取游戏规则` / `设游戏规则` | 名称 [值] | `MinecraftServer#getGameRules()` + 规则键映射 | 待版本确认 |
| `保存世界` | — | `MinecraftServer#saveEverything` / 区块保存 | 待版本确认 |
| `取睡眠状态` | — | `ServerLevel` 睡眠状态 API | 待版本确认 |
| `区块已加载` | 维度 区块X 区块Z | `ServerLevel#isLoaded` / `getChunkSource` | 确认 |
| `取TPS` / `取MSPT` | — | `MinecraftServer#getAverageTickTime` + 桥接层滑动窗口 | 待版本确认 |
| `取服务器tick` | — | `MinecraftServer#getTickCount()` | 确认 |
| `取协议版本` / `取服务器版本` | — | 服务端状态 / `SharedConstants` | 待版本确认 |
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
| `悬浮字` | 维度 X Y Z 文本 [缩放] [上升] [时长] | `Display.TextDisplay`：`EntityType.create(CompoundTag, Level)`（键名与 `/summon` 一致，1.21.1 的 setter 全是 private），宿主逐 tick `setPos` 上浮、到时 `discard` | 已落地 |

## 6. 交互 / 事件

| `世界` 方法 | 参数 | API | 状态 |
| --- | --- | --- | --- |
| `提示` | 玩家 文本 | `ServerPlayer#sendSystemMessage(Component)` | 确认 |
| `广播` | 文本 | `PlayerList#broadcastSystemMessage(Component, false)` | 确认 |
| `执行命令` | 命令 | `MinecraftServer#getCommands().performPrefixedCommand(...)` | 确认 |
| `取消事件` | — | 桥接层本次事件槽位 → `event.setCanceled(true)`（通用事件桥里对实现 `ICancellableEvent` 的事件同样生效） | 确认 |

> 事件源不止上表：宿主内置 6 个中文事件、通用事件桥按订阅对接 `nebula/events.json`
> 里的 262 个 NeoForge 事件类（`EventBridge` + `eventTriggers(handle)`）。清单由
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
| `点击消息` | 玩家 段JSON | 一行可点击聊天：段是 `[{文,命令,悬停}]`，宿主给每段挂 `ClickEvent.RunCommand`；点击即以玩家身份执行命令 | 确认（1.21.1 实测） |
| `输入界面` | 玩家 标题 回调 | 铁砧命名框当文本框：存函数值闭包，发 `输入界面` 动作（带 `界面ID`）；输入框初始为空，点成品格时 `clickGui` 回传 `{玩家名,文本,动作}`，其它槽位只读 | 确认（1.21.1 实测） |
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
| tick 冻结 / 步进 / 加速 | 1.20.3 起有原生 `/tick`，1.21.1 已用命令 + `ServerTickRateManager` 落地；真机验证 frozen / rate 40 / unfreeze 都生效 | 无需 Mixin |
| 客户端本地背包界面 | 只有 `打开背包` 仍是占位（服务端不管理 `InventoryScreen`）；`设置区块半径` / `重发区块` 已用原生封包落地 | 用 `创建界面` + `打开界面` 打开箱子界面 |
| 客户端侧动作（粒子 / 标题 / 音效）执行端 | 服务端发命令 / 发包 | 统一走服务端，不需要客户端 mod |
| SNBT 实体生成 | 原版 SNBT 解析器不公开；改为 `summon 类型 坐标 [NBT]`，NBT 不含空格 | 需要完整 SNBT 时再引入内部解析器 |
| 版本浮动 API（种子 / 生物群系 / 游戏规则 / 协议版本 / 属性 / FoodData） | 反射读取，缺失时返回空串 / 默认值；`gamerule` 只取到规则值文本 | 升级 MC 版本后跑一次查询冒烟，必要时补精确签名 |
| 睡眠状态 / 侧边栏分数行 | 睡眠状态按反射结果返回是否；侧边栏只显示标题 | 需要分数行时扩展 `设置侧边栏` 的参数 |
