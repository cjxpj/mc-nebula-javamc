# NeoForge 服务端设计与实现现状

> 最后校准：2026-11-09。本文既记录设计决策，也标注代码里**真正落地了什么**。实现以
> `mod/src/main/java/nebula/neoforge/` 与 `bridge/src/lib.rs` 为准；逐方法映射见
> [`mc-api-map.md`](mc-api-map.md)，平台取舍见 [`platform-choice.md`](platform-choice.md)，
> 三份文档的分工见 [`README.md`](README.md)。
>
> 标注口径：**已落地** = 代码里有对应实现；**规划** = 设计上保留、当前未实现；**已废弃** =
> 早期设计后来被更简单的方案取代，保留说明避免有人照旧方案返工。

## 1. 目标与边界

- **宿主**：Minecraft Java 版专用服务端（1.21.11 / NeoForge 21.11.42 / Java 21）。
- **词库**：`<gameDir>/nebula/<插件名>/`，入口 `main.n`，同目录顶层 `.n` 自动合并。
- **桥接**：NeoForge mod 通过 JNI 调用 Rust `cdylib`，Rust 静态链接 `crates/nebula`。
- **业务**：全部写在 `.n` 词库；Java 侧只做事件转发、命令注册、`世界` 原语与界面容器。
- **不做**：客户端 mod、网络封包、词库下发、跨进程 IPC。
- **已落地规模**：128 个 `世界` 方法（39 类查询分支 + 动作），5 个随服词库插件，一键回归自检 19/19 PASS。

## 2. 运行时架构

```text
NeoForge 专用服务端
  ├─ mods/nebula-neoforge-bridge-0.2.0.jar
  │    ├─ nebula/neoforge/
  │    │    ├─ NebulaMod.java       @Mod 入口：事件监听、/nebula 注册、动作落地
  │    │    ├─ NativeBridge.java    JNI 声明 + 原生库解压加载
  │    │    ├─ McApi.java           查询回调：读世界 → 文本 / JSON
  │    │    ├─ McActions.java       动作落地：原版命令或稳定 API
  │    │    ├─ McSupport.java       反射兜底、实体查找、物品快照、tick 统计
  │    │    └─ GuiManager.java      箱子界面对象、点击拦截、会话清理
  │    ├─ META-INF/neoforge.mods.toml
  │    └─ META-INF/native/windows-x86_64/nebula_jni.dll
  ├─ config/nebula-bridge/native/nebula_jni.dll   ← 运行时解压目标
  └─ nebula/<插件名>/main.n …
```

数据流：

```text
NeoForge 事件（服务端主线程）
  → NebulaMod 组装 payload JSON
  → NativeBridge.dispatch / dispatchCommand / clickGui
  → Rust 引擎分发到词库（事件帧 / [指令] 词条 / 界面点击闭包）
  → 词库调用 $世界.方法 …$
      查询：Rust → JNI → McApi.query（同步读世界，立即返回）
      动作：Rust 收集进 actions 队列，dispatch 返回后由宿主 executeActions 落地
  → 输出行由宿主广播 / 回给玩家；取消标志写回本次事件
```

> **动作是延迟执行的**：同一词条里 `$世界.设方块 …$` 之后立刻 `$世界.取方块 …$` 读到的仍是
> 旧值，要到下一次事件 / reload 才生效。这是查询同步、动作走队列的直接后果，写词库时必须记住。

早期架构图里的 `ServerLifecycle` / `EventBridge` / `ServerTaskQueue` / `NebulaCommand`
**未落地**：生命周期、事件桥与命令注册目前都集中在 `NebulaMod`，异步结果回主线程尚未实现。

## 3. mod 入口与生命周期

### 3.1 入口

```java
@Mod(NebulaMod.MODID)   // MODID = "nebula_bridge"
public final class NebulaMod {
    public NebulaMod() { /* 只注册事件监听 */ }
}
```

构造函数**不读配置、不定位路径、不加载原生库、不创建引擎**（此时服务端世界尚未就绪）。
实际注册 12 个监听：`ServerStartedEvent`、`ServerStoppingEvent`、`ServerChatEvent`、
`LivingDamageEvent.Post`、`LivingHealEvent`、`BlockEvent.BreakEvent`、
`RegisterCommandsEvent`、`PlayerContainerEvent.Close`、`PlayerLoggedInEvent`、
`PlayerLoggedOutEvent`、`ServerTickEvent.Pre/Post`。

此外 `EventBridge` 在开服后按词库订阅**动态**注册其它 NeoForge 事件监听器（见 5.2），
不在构造期写死；没有插件订阅时不挂任何额外监听器。

### 3.2 生命周期表

| 阶段 | 事件 | 实际动作 |
| --- | --- | --- |
| mod 构造 | `@Mod` 构造函数 | 注册上面 10 个监听，仅此而已 |
| 服务端启动完成 | `ServerStartedEvent` | `NativeBridge.load()` → `create(<gameDir>/nebula)` → `loadAll()` → `executeActions`（落地插件指令注册） |
| 每 tick | `ServerTickEvent.Pre/Post` | 只调 `McSupport.onTickPre/Post`，用 40 tick 滑窗算 TPS / MSPT / 服务器 tick；**不直接调用 JNI**（订阅 tick 事件的插件由通用事件桥节流到 20 tick 一次） |
| 插件订阅变化 | 开服 `loadAll` / `/nebula load\|reload\|unload\|debug\|undebug` | 调 `EventBridge.refresh(handle)`：读 Rust 侧订阅表，增量增删 `IEventBus` 监听器 |
| 命令注册 | `RegisterCommandsEvent` | 注册 `/nebula` 及其子命令，清空插件指令映射 |
| 关界面 / 退出 | `PlayerContainerEvent.Close` / `PlayerLoggedOutEvent` | 清理界面会话、释放点击闭包 |
| 服务端停止 | `ServerStoppingEvent` | `GuiManager.clearAll()`、清空插件指令、`NativeBridge.destroy(handle)` |

两点容易误判：

- `destroy` 只清空界面会话并 `drop(Bridge)`，**不会逐个执行 `[f]卸载`**；只有 `/nebula unload` 会跑卸载钩子。
- 词库根固定为 `FMLPaths.GAMEDIR.get().resolve("nebula")`，没有配置文件可改。

### 3.3 命令

| 命令 | 行为 |
| --- | --- |
| `/nebula list` | 列出插件及调试开关状态 |
| `/nebula load\|reload\|unload <插件名\|--all>` | 加载 / 重载 / 卸载 |
| `/nebula debug\|undebug <插件名\|--all>` | 开关词库文件改动自动重编译 |

控制台始终可用，玩家需 OP。早期设计里的 `/nebula 自检` **未落地**：自检能力由
`mod/selftest/` 的独立测试服脚本提供。`[f]自检` 是词库侧约定，不是 `/nebula` 子命令。

**插件指令**由词库自己注册，不再写进 `nebula.ini`：`[f]初始化` 里
`$世界.注册指令 商店 0 shop 泥土商店$`，`[f]卸载` 里 `$世界.注销指令$`。宿主在
`executeActions` 里增删 Brigadier 节点并 `sendCommands` 重发命令树；执行时按规范名匹配
`[指令]商店|shop` 词条；没有对应词条时返回 `ok=false`。

### 3.4 tick 与异步：设计与实现的差异

| 早期设计 | 现状 |
| --- | --- |
| 每 tick 调 `NativeBridge.tick(handle, tickCount)` | **已废弃**：JNI 契约里没有 `tick`，tick 事件只用于统计 |
| 热重载每 20 tick 轮询一次指纹 | **已废弃**：debug 插件在**每次事件分发前**比一次指纹 |
| 编译放工作线程、成功后原子替换 | **已废弃**：`compile_plugin` 在调用线程同步执行，成功后用 `Mutex` 替换注册表 |
| `$异步$` 结果投递 `ServerTaskQueue` 回主线程 | **规划**：该类未落地；当前 `世界` 查询/动作都在事件分发的主线程同步完成 |

结论：现在不存在跨线程的 `世界` 调用，因此也就没有「非主线程访问 Minecraft 对象」的路径；
代价是一次事件处理必须短小，长 IO 仍应避免（词库侧 `$异步$` 由 Rust 引擎维护）。

## 4. 词库加载与热重载

### 4.1 目录与元数据

```text
<gameDir>/
├── mods/nebula-neoforge-bridge-<version>.jar
├── config/nebula-bridge/native/nebula_jni.dll   ← 原生库解压目录（不是配置）
└── nebula/                                      ← 词库根（固定）
    └── <插件名>/
        ├── main.n
        ├── 其它.n          ← 目录顶层自动合并
        ├── nebula.ini      ← 元数据，桥接层不解析
        └── *.json          ← 插件自己的配置 / 商品数据
```

- 一个文件夹 = 一个插件，文件夹名即插件名；`_` / `.` 开头的目录禁用。
- 入口 `main.n` 优先；没有 `main.n` 时只允许目录内唯一一个 `.n`；单文件 `$引入` 不再支持。
- `nebula.ini` 目前是纯元数据（`名称` / `作者` / `地址` / `版本` / `描述`），**桥接层不读取**；
  早期用它声明「指令」的做法已废弃。
- 编译时会把插件目录注入成 `%库目录%`（词库 root，函数帧也能读），配置文件用
  `$读文件 %库目录%/x.json$` 读取；`.json` 也纳入 debug 指纹。
- `%时间%` / `%版本%` 是引擎内置系统变量，词库同名赋值会被遮蔽，变量名要加前缀。

### 4.2 生命周期钩子（早期文档写错了，以此为准）

| 钩子 | 触发时机 | 适合做什么 |
| --- | --- | --- |
| `[f]@头部` / `[f]初始化` | **每次编译**：load / loadAll / reload / debug 自动重编译 | 配置、状态、注册指令 |
| `[f]加载` | 仅 `load` / `loadAll`（插件首次插入） | 「就绪」提示；reload 不跑 |
| `[f]重载` | 仅 `reload` | 重载后的读回验证 |
| `[f]卸载` | 仅 `/nebula unload`；关服 `destroy` 不跑 | 注销指令、释放资源 |

`compile_plugin()` 编译成功后先跑 `@头部` + `初始化`，再由调用方按操作类型跑 `加载` / `重载`。
**debug 自动重编译只跑 `初始化`**，不会跑 `加载` / `重载` / `卸载`——这是自检词库把
「就绪」提示放在 `[f]加载`、把读回验证放在 `[f]重载` 的原因。

其它编写约束：布局敏感（块外空行分隔词条，`如果尾` / `结束` 必须闭合）；一行只能一个
`$调用$` 且**结尾 `$` 不能漏**；`[函数]` 帧不继承调用方作用域，配置 / 状态要显式传参；
事件帧的父作用域是 `plugin.state`；函数值用 `回调:[函数] … 结束` 定义，作为实参传入后
在被调时落到 `%参数1%`。详见 [`bridge/README.md`](../../bridge/README.md) 第 4、5 节。

### 4.3 热重载与调试

- `/nebula reload`：先编译新版本，成功才替换；词库写坏时旧版本继续可用。
- `/nebula debug`：每次事件分发前比一次插件目录内全部 `.n` + `.json` 的「文件名 + 修改时间」
  指纹；变了就同步重编译并替换，失败保留旧版本并记下这次指纹，同一份坏文件不会反复刷日志。
- 手动 reload 不清除调试开关；`unload` 后重新 `load` 回到非调试状态。
- 卸载插件会同时清掉它创建的界面对象回调，避免悬空闭包。

## 5. 事件桥、指令与界面

### 5.1 内置事件（6 个中文词条）

| 词库词条 | NeoForge 事件 | 可取消 | payload 要点 |
| --- | --- | --- | --- |
| `[事件]玩家聊天` | `ServerChatEvent` | 是 | `玩家` / `玩家名` / `消息` / `管理员` / `潜行` / `维度` / `x,y,z` |
| `[事件]实体受伤` | `LivingDamageEvent.Post` | 否（伤害已结算） | `攻击者` / `实体` / `伤害` / `剩余` / `身高` / `暴击` / `原因` / `致死`；只转发玩家造成的伤害，暴击按 `Player#attack` 的条件复算 |
| `[事件]实体治疗` | `LivingHealEvent` | 否（回血已发生） | `实体` / `数值`（按上限截断后的真实回复）/ `之前` / `剩余` / `身高` / `原因`（自然回复 / 效果 / 其它）/ `满血`；已满血直接不转发 |
| `[事件]方块破坏` | `BlockEvent.BreakEvent` | 是 | 玩家 + 方块 + 坐标快照 |
| `[事件]玩家进入` | `PlayerLoggedInEvent` | 否 | `玩家` / `玩家名` / `管理员` / `维度`；每次连接都触发，登录插件用它重建会话 |
| `[事件]每秒` | `ServerTickEvent.Post`（每 20 tick） | 否 | `服务器tick` / `在线人数`；给倒计时、超时踢人这类没有平台事件的逻辑用 |

约定：payload 只含基础类型与 JSON，不跨线程持有 Minecraft 对象；事件异常只记日志并跳过该插件；
取消标志由 Rust 写入本次事件槽位，宿主在 `executeActions` 前后统一 `event.setCanceled(true)`。
事件输出行会被宿主广播 / 回给玩家，私聊用 `$世界.提示$`。
这 6 个是语义定制的中文事件；其余 NeoForge 事件类走 5.2 的通用事件桥。

### 5.2 通用事件桥（事件总线全量映射）

`mod/tools/gen_events.py` 在构建期从 `neoforge-21.11.42-universal.jar` 抽出
`net/neoforged/neoforge/event/**` 下的 267 个事件类，写成 jar 内的
`nebula/events.json`（`name` = Java 类简名，内部类用 `.` 代替 `$`；`class` = 完整类名；
`throttle` = tick 类事件的节流周期；`hint` = 加载期事件提示）。

- **订阅驱动**：Rust 侧 `eventTriggers(handle)` 汇总所有插件 `dic_funcs["事件"]` 的
  trigger 名；`EventBridge.refresh` 与当前 `ACTIVE` 表做 diff，用
  `IEventBus.addListener(EventPriority, boolean, Class, Consumer)` 注册新增的、
  `unregister(consumer)` 摘掉不再订阅的。内置中文事件不在清单里，不会重复分发。
- **payload**：反射遍历 public getter，按 Java 字段名注入（`getEntity()` → `%entity%`），
  复杂对象结构化；未知类型只留短字符串。实现 `ICancellableEvent` 的事件支持
  `$世界.取消事件$`。
- **成本约束**：没有订阅就零额外 JNI；`EntityTickEvent` / `PlayerTickEvent` /
  `LevelTickEvent` / `ServerTickEvent` 这类每实体 / 每 tick 事件按 20 tick 节流。
  注册期 / 客户端专用类运行时被过滤，专用服收不到加载期事件时会记 WARN。
- **信任链**：`build.ps1` 把清单打进 jar，`regress.py` 按内容比对 jar 内清单与源码，
  升级 NeoForge 后不重新生成 + 全量构建就会报「产物与源码不一致」。

词库写法见 [`apis/事件.md`](apis/事件.md) 末节与 [`plugin-guide.md`](plugin-guide.md) 第 4 节。

### 5.3 界面与点击（不再需要 Mixin）

早期把「物品栏点击事件」列为待拍板、认为必须 Mixin。`GuiManager` 用自定义 `ChestMenu`
落地后，这条路已经不需要了：

- `创建界面` 返回界面对象 id → `界面物品` / `界面数据` 只操作该对象 → `打开界面 面 回调` 把
  `回调:[函数] … 结束` 闭包交给宿主；点击时 `NebulaMenu#clicked` 全拦截（不移动物品），
  按菜单绑定的界面 id 调 `clickGui(handle, id, payload)`，Rust 直接执行闭包，payload 落在 `%参数1%`。
- 界面统一用对象写法；缺少界面对象 id 的点击会被忽略并记 WARN。
- 关窗 / 退出 / 卸载 / `destroy` 都会走 `closeGui` 释放闭包；同一玩家只保留最后一个打开的界面对象。
- 覆盖面只到「词库自己打开的箱子界面」，原版容器与客户端本地背包界面不接管。

### 5.4 唯一占位

`$世界.打开背包$`：玩家背包是客户端本地 `InventoryScreen`，服务端不管理，宿主只记一条 WARN。
需要界面时用 `创建界面` + `打开界面` 打开箱子界面。

## 6. 线程模型

- **硬约束不变**：Minecraft / NeoForge API 只能在服务端主线程调用；词库事件分发、`世界`
  查询与动作落地全部同步发生在主线程。
- 早期设计里「`McApi` 入口统一检查 `server.isSameThread()`」**未落地**——代码里没有这层断言。
  当前之所以安全，是因为所有入口都从事件回调同步进来，没有第二条线程能触达 `McApi`。
- 编译 / 热重载同步执行，替换靠 `Mutex`；`plugin.state` 与异步任务带 epoch，过期回调丢弃。
- 词库事件里不要长时间 IO；`$异步$` 结果回主线程的 `ServerTaskQueue` 仍是规划项。

## 7. 客户端 / 服务端边界

- 只支持专用服务端；`neoforge.mods.toml` 里 `neoforge` / `minecraft` 依赖均标
  `side = "SERVER"`，词库不下发客户端，客户端不装 mod 也能进服。
- 早期设想的 `Dist` 守卫**未落地**：包里没有客户端专属类，靠「只在服务端事件里创建引擎」隔离。
- 集成服务端（单人游戏）支持仍是独立评估项，不在当前范围。

## 8. 构建、部署与配置

### 8.1 版本（已锁定，不再是待拍板）

| 项 | 当前值 |
| --- | --- |
| Minecraft | 1.21.11 |
| NeoForge | 21.11.42（依赖范围 `[21.11,21.12)`） |
| Java | 21（服务端自带 `.jdk/jdk-*`） |
| Rust | stable，`cdylib`，`jni` crate |
| 构建 | **没有 Gradle**：`build.ps1` 直接调 `cargo build --release` + `javac` + `jar` |

**1.21.11 实现要点**：注册表 id 用 `Identifier`、`GameProfile.name()` 取用户名、
`Inventory` 字段私有化后走 `McSupport.inventorySlots()` 槽位视图、
`ComponentSerialization.CODEC` 序列化聊天组件、`EntityType.create(TagValueInput, …)` +
`EntitySpawnReason.COMMAND` 建实体，权限判断走 `Commands.hasPermission(LEVEL_*)` /
`permissions().hasPermission(...)`。能用公开 API 的地方直接调用（种子 / 协议号 / 时间 /
FoodData / 实体交互），只有游戏规则、属性注册表与事件 getter 这类需要动态枚举的场景保留反射。

`mods.toml` 依赖范围锁死为 `[1.21.11,1.21.12)` / `[21.11,21.12)`：其它 MC 版本有破坏性 API
变更，用错版本编译的 jar 会因常量池方法引用懒解析而「服起得来、玩家一进就崩」。事件清单由
`python mod/tools/gen_events.py` 从 21.11.42 的 universal jar 抽取（当前 267 个事件类），
升级 NeoForge 必须重新生成。

早期推荐的 ModDevGradle 2.x / NeoGradle 7.x 方案**未采用**：工程规模小，直接用服务端
`libraries` 里的官方映射 jar 编译更省事。若将来引入 Mixin 或多模块再评估。

### 8.2 构建脚本

```powershell
powershell -ExecutionPolicy Bypass -File mod/build.ps1            # 全量
powershell -ExecutionPolicy Bypass -File mod/build.ps1 -SkipRust   # 只改 Java
```

四步：`cargo build --release` → `javac`（classpath 取 `server-1.21.11/libraries` 下除
`-slim` / `-unpacked` 外的 jar，这两类带 SRG 名会编译失败）→ 复制 `neoforge.mods.toml`
与 `nebula_jni.dll` 到 `build/classes/META-INF/` → `jar --create` 并部署到 `server-1.21.11/mods/`。
`-RustTargetDir` 可指定 Rust 产物目录。`-SkipRust` 要求 `target/release/nebula_jni.dll` 已存在。
脚本最后会打开 jar 比对内嵌 dll 与 release dll 的 SHA256——`Deployed` 只证明复制成功，不证明内容新。
构建时还会把 `bridge/src/**/*.rs`、`Cargo.toml`、`src/main/java/**/*.java` 的逐文件 SHA256
写进 jar 内 `META-INF/nebula-build.json`。全量构建还会在 `target/release/` 落下
`nebula_jni.manifest.json`——与 dll 哈希绑定的一手记录，写明「这份 dll 由哪份 Rust 源码编出」；
`-SkipRust` 不重编 dll，只读这份清单（不再信上一个 jar 的自述），清单缺失或 dll 哈希不符即失败。
`bridge/build.rs` 在编译期把 `src/**/*.rs` + `Cargo.toml` 的逐文件 SHA256 经 `cargo:rustc-env`
烧进 dll 的 `.rodata`（`NEBULA_SOURCES=` 标记）；`-SkipRust` 会比对清单记录与该标记，不一致即失败。

### 8.3 原生库加载

`NativeBridge.load()` 按 `os.name` / `os.arch` 拼出平台目录，从 jar 内
`/META-INF/native/<platform>/` 解压到 `<gameDir>/config/nebula-bridge/native/`，再 `System.load`。
`platform()` 能识别 windows / macos / linux × x86_64 / aarch64，但**构建脚本目前只打包
windows-x86_64**，其它平台需先扩展脚本。

### 8.4 配置现状

早期草案里的 `config/nebula-bridge.toml`（`wordlib.root` / `debug_poll_ticks` /
`scan_budget` / `event.player_move`）**不存在，也没有被读取**。当前约定是：词库根固定、
debug 靠命令开关、事件集写死在 `NebulaMod` 构造函数里。`config/nebula-bridge/` 目录只放
解压出来的原生库。要支持可配置，得同时改 Java 侧与 Rust 侧，作为后续项。

### 8.5 一键回归

`python mod/selftest/regress.py`（`--keep` 保留现场）：准备隔离服（端口 25566 /
RCON 25576，目录联接共享 `libraries` / `.jdk`，独立 `level-name`）→ 启动两个 mineflayer
假玩家 → 跑 `selftest/wordlib` → 按 `selftest/checks.json` 用原版 `execute if …` /
`data get entity …` 批量反查 → 恢复 tick、RCON `stop`、清理。实测 **19/19 PASS、0 告警**。

启服前先做**产物校验**：jar 内嵌 dll 与 `bridge/target/release/nebula_jni.dll` 的 SHA256 一致，
并逐文件比对 `META-INF/nebula-build.json` 记录的源码内容指纹与工作区现状；
不一致会报出具体变动文件并中止，避免用旧 jar 得出假 PASS。
按**内容**而非修改时间判断，所以编辑器空保存不会误报；jar 无 manifest 时才退回 mtime 兜底。
另外会拿 `target/release/nebula_jni.manifest.json` 做交叉校验（dll 哈希 + Rust 指纹），
确保 jar 里的指纹来自 dll 产出时的一手记录，而不是上一个 jar 的转述。
**最终一手证据来自 dll 自身**：回归脚本从 jar 内 dll 的字节里解析 `NEBULA_SOURCES=` 标记，
与当前源码逐文件比对。即使 jar manifest 与原生库清单被同时伪造，只要 dll 没重编就会被拦住。
信任链到此为止——再往上需要代码签名或可信构建，超出本地项目的范围。
只想秒级检查用 `python mod/selftest/regress.py --verify-only`；`--skip-verify` 不推荐。
升级 Minecraft / NeoForge 版本前先跑它，断言写在 `checks.json`。清理时先摘目录联接再删目录，
**绝不碰 `server-1.21.11/world`**。线上服未开 RCON、stdin 不可注入，不要用 `taskkill` 代替 `stop`。

## 9. 决策台账

### 已定

1. **版本**：Minecraft 1.21.11 / NeoForge 21.11.42 / Java 21。
2. **构建**：`build.ps1` + `javac`，不引入 Gradle。
3. **词库根**：`<gameDir>/nebula`（固定，不可配置）。
4. **运行模式**：只支持专用服务端。
5. **物品栏点击**：自定义 `ChestMenu` 拦截，不引入 Mixin；`打开背包` 保持占位。
6. **原生库**：jar 内打包 + 运行时解压到 `config/nebula-bridge/native/`。
7. **mod 元信息**：`modId = nebula_bridge`、`displayName = Nebula Bridge`、`license = All Rights Reserved`。
8. **平台矩阵**：先只做 Windows x64；Linux / macOS 待扩展构建脚本。
9. **插件指令**：词库自注册，不再读 `nebula.ini`。

### 仍开放

- Linux / macOS（含 aarch64）原生库打包与跨平台冒烟。
- `nebula-bridge.toml` 配置化：词库根、事件开关、`scan_budget` 等。
- `ServerTaskQueue`：`$异步$` 结果回主线程的正式通道。
- 监听任意容器（含原版容器 / 玩家背包）时才考虑 Mixin `AbstractContainerMenu#clicked`。
- 集成服务端支持；侧边栏分数行；完整 SNBT 实体生成。
- 通用事件桥的高频事件按需分频（当前固定 20 tick 节流）。

## 10. 验证清单

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 目录与基岩版对应、词库结构一致 | ✅ | `server-1.21.11/nebula/` 与 `bridge/README.md` |
| 生命周期覆盖启动 / tick / 停止 / 命令，构造期不碰世界 | ✅ | `NebulaMod#onServerStarted` / `onServerStopping` |
| 热重载失败回退、旧任务 epoch | ✅ | `op_reload` 先编译后替换；`bridge/src/lib.rs` 单测 |
| 每个 `世界` 方法都能对应到实现或明确降级 | ✅ | 128 个方法全量落地，唯一占位 `打开背包`；见 `mc-api-map.md` |
| 主线程同步、无跨线程 `世界` 调用 | ✅ | 动作走 `executeActions`，无 JNI tick |
| 客户端/服务端边界明确 | ✅ | `side = "SERVER"`，客户端无需装 mod |
| 构建产物布局可执行 | ✅ | `build.ps1` 构建部署成功；jar 已在 `server-1.21.11/mods/` |
| 真机验证 | ✅ | 1.21.11 / 21.11.42：查询、动作、双假玩家、悬浮字 / 治疗 / 通用事件桥动态注册，19/19 PASS、0 告警；见 `mc-api-map.md` 第 0 节 |
| Rust 单测 | ✅ | `cargo test --offline --lib` 7/7 通过（含文档 ↔ 代码双向断言、随服词库编译） |
| 一键回归 | ✅ | `regress.py` 19/19 PASS、0 告警 |
