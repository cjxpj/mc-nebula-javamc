# Rust JNI 桥接层（平台无关）

本目录对应 `bedrock/bridge/`：Rust 侧静态链接 `crates/nebula`，通过 JNI 暴露一个窄接口给 Java 宿主。目前宿主只有 NeoForge 一侧；**JNI 契约与词库加载规则本身与平台无关**，将来若接入其他宿主，差异只落在事件源与 `世界` 实现。

设计文档索引：[`../mod/docs/README.md`](../mod/docs/README.md)；设计细节：[`../mod/docs/neoforge.md`](../mod/docs/neoforge.md)；`世界` 对象映射：[`../mod/docs/mc-api-map.md`](../mod/docs/mc-api-map.md)。

> 注入对象统一为 `世界`（`$世界.方法 …$`）。
> `publish_world()` 同时向引擎注册两个类名，已有词库可以逐步迁移。

## 1. 组件边界

| 层 | 职责 | 不做什么 |
| --- | --- | --- |
| Java 宿主（NeoForge mod） | 监听平台事件、注册 `/nebula`、线程调度、加载原生库、实现 `McApi` | 不解析 `.n`、不执行业务逻辑 |
| Rust 桥接层（`nebula_jni`） | 词库扫描 / 编译 / 热重载、事件分发、广播、`世界` 调用转发 | 不直接链接 Minecraft 类 |
| 词库（`.n`） | 生命周期、事件处理、业务逻辑 | 不直接调用 Java API，只调用 `世界` |

## 2. JNI 契约

```java
native long    create(String wordlibRoot);
native void    destroy(long handle);
native String  loadAll(long handle);
native String  load(long handle, String target);
native String  reload(long handle, String target);
native String  debug(long handle, String target);
native String  undebug(long handle, String target);
native String  unload(long handle, String target);
native String  list(long handle);
native String  dispatch(long handle, String event, String payloadJson);
native String  dispatchCommand(long handle, String plugin, String command, String payloadJson);
native String  clickGui(long handle, long guiId, String payloadJson);
native void    closeGui(long handle, long guiId);
```

- 符号名按宿主类包名生成：`Java_nebula_neoforge_NativeBridge_create` 等。
- `handle` 是 Rust 侧 `Box` 化引擎指针；`destroy` 后不得再使用。
- 所有返回都是 JSON 文本；`dispatch` / `dispatchCommand` / `clickGui` 返回 `{ok?, summary?, outputs, actions, cancel}`，异常在 Rust 侧捕获，不向 Java 抛 panic。
- `dispatchCommand` 按别名匹配 `[指令]` 词条；没有对应词条时返回 `ok=false`。
- `clickGui` 按界面对象 id 找回 `$世界.打开界面$` 存下的闭包，把 payload 作为 `%参数1%` 调用；`closeGui` 释放该对象的闭包与捕获变量。
- 插件指令由词库 `[f]初始化` 的 `$世界.注册指令$` 产生动作，宿主在 `executeActions` 里注册；不再扫描 `nebula.ini`。

## 3. payload JSON

Java 侧把事件字段整理成 JSON，Rust 侧解析后注入词库变量：

```json
{
  "插件": "示例",
  "玩家": { "名称": "Steve", "uuid": "...", "维度": 0, "x": 0, "y": 64, "z": 0, "模式": "生存" },
  "方块": { "名称": "minecraft:oak_log", "维度": 0, "x": 0, "y": 64, "z": 0, "数据": "..." },
  "坐标": { "x": 0, "y": 64, "z": 0 },
  "消息": "..."
}
```

- 字段命名与基岩版一致（`%玩家%` / `%方块%` / `%坐标%` / `%维度%` / `%消息%` …）。
- 只传基础类型与 JSON，不跨线程持有 Minecraft 对象。
- 事件异常不抛出到平台事件总线；记录日志并跳过该插件。

## 4. 词库加载

```text
<wordlibRoot>/<插件名>/
├── main.n        ← 入口
└── 其它.n        ← 同目录顶层自动合并
```

- 一个文件夹 = 一个词库；文件夹名即插件名。
- 入口 `main.n` 优先；没有 `main.n` 时只允许目录内唯一一个 `.n`。
- 目录顶层全部 `.n` 自动合并；单文件 `$引入` 不再支持。
- `_` / `.` 开头的目录禁用。
- 生命周期：`[f]初始化` / `[f]加载` / `[f]重载` / `[f]卸载` / `[f]自检`。
- 热重载：工作线程编译 → 主线程原子替换；失败保留旧版本；旧异步任务带 epoch，过期回调丢弃。
- `[f]初始化`（与 `@头部`）在**每次编译**都执行：load / loadAll / reload / debug 自动重编译都会跑，适合配置、状态与注册指令。`[f]加载` 只在插件首次插入时跑（reload 不跑），`[f]重载` 只在 reload 跑，`[f]卸载` 只在 `/nebula unload` 跑（关服 `destroy` 不跑）。详见 [`../mod/docs/neoforge.md`](../mod/docs/neoforge.md) 第 4.2 节。
- `[f]初始化` 不注册路由 / 长连接，不读默认模板文件。

### 调试自动重载

命令 `/nebula debug <插件名|--all>` 开启后，桥接层在每个事件分发前比一次插件目录里全部 `.n` 的
「文件名 + 修改时间」指纹（`plugin_stamp`），变了就重编译并原子替换，**不用再手动 reload**。
`/nebula undebug <插件名|--all>` 关闭；`/nebula list` 会标出正在调试的插件。

- 指纹只覆盖目录顶层的 `.n`，与 `build_dic_from_dir` 的收集范围一致；新增 / 删除词库文件同样会触发。
- 编译失败时继续用旧版本，并记下这次指纹，同一份坏文件只报一次错、不会每次事件都刷日志。
- 手动 `reload` 不会清掉调试开关；`unload` 之后重新 `load` 则回到非调试状态。
- 检查开销是每个 debug 插件一次 `read_dir` + 几次 `metadata`，非 debug 插件完全不走这一步。


## 5. `世界` 调用链

```text
词库执行 $世界.取方块 …$
  → Rust 内置注入类 mc
  → JNI 回调宿主 McApi（仍在服务端主线程）
  → Minecraft / NeoForge API
  → 结果序列化为 JSON / 字符串返回词库
```

- 查询类失败返回空串 / `{}` / `[]`；动作类失败返回空串并记日志。
- 取消通过 `$世界.取消事件$` 写本次事件槽位，宿主在分发结束后统一应用。
- 逐方法映射见 [`../mod/docs/mc-api-map.md`](../mod/docs/mc-api-map.md)。

### 5.0 全量方法与表驱动注册

映射文档里的 124 个 `世界` 方法**全部注册成 `世界` 注入类里的函数**，词库直接 `$世界.取方块 …$` /
`$世界.设方块 …$` 调用即可。实现分三层：

| 层 | 位置 | 职责 |
| --- | --- | --- |
| 规格表 | `src/lib.rs` `register_table_driven()` | 每个方法声明参数位次与类型（`Str` / `Int` / `Float` / `Bool` / `Text` / `Player`）；查询类构造请求 JSON 调 `McApi.query`，动作类压入动作队列 |
| 查询 | 宿主 `McApi.java` | 读世界后返回文本 / JSON 文本；版本浮动的方法走 `McSupport` 反射兜底 |
| 动作 | 宿主 `McActions.java` | 原版命令或稳定 API 落地；`McSupport` 提供实体查找、tick 统计与通用工具 |

- 现有的 20 个手写 hook（界面对象 / 指令注册 / 取消事件 / 取相邻方块 / 取物品数等）保持不变，规格表只补缺失项。
- 参数位次、字段命名与「是/否」转换规则写在 `mod/docs/mc-api-map.md` 第 0 节；两者由单测
  `every_mapped_mc_method_has_a_function` 双向锁定：`cargo test --offline --lib`。增删方法的
  四步流程（文档表格 / 规格表 / 宿主分支 / 测试）也写在该节末尾。
- `取TPS` / `取MSPT` / `取服务器tick` 由宿主统计 `ServerTickEvent.Pre/Post` 的 40 tick 滑窗。
- 原先的 7 个占位已消除 6 个：tick 四方法走原生 `/tick` 命令 + `ServerTickRateManager`（1.20.3 起就有，不需要 Mixin），`设置区块半径` / `重发区块` 走 `ClientboundSetChunkCacheRadiusPacket` / `ClientboundLevelChunkWithLightPacket`。**目前只剩 `打开背包` 一个占位**（玩家背包是客户端本地 `InventoryScreen`），详见映射文档「明确降级 / 占位」。

### 5.2 词库调用注意（1.21.11 真机实测）

- `%时间%`（`Value::Time`）/ `%版本%`（引擎版本）等是引擎内置系统变量，词库给同名变量赋值会被遮蔽，变量名要加前缀。
- 动作是**延迟执行**的：同一词条里 `$世界.设方块 …$` 之后立刻 `$世界.取方块 …$` 读到的仍是旧值，要到下一次事件 / reload 才能读到效果。
- 方块类查询对未加载区块返回空串 / `{}` / `[]`；先 `$世界.区块已加载$`，或用 `/forceload add` 强加载。
- `$世界.执行命令 say …$` 受 256 字符 chat 上限；`取实体列表 维度 上限` 可限制条数，`取方块标签` / `获取方块` 的长结果要分批输出。
- 宿主命令改走 Brigadier `parse + execute`，真正的 `CommandSyntaxException` 会打 WARN；原版把「状态没变 / 粒子无人可见 / objective 不存在」也做成异常，宿主统一过滤，只剩真失败（目标不存在、区块未加载、UUID 被拒绝）。`设置侧边栏` 改用 Scoreboard API 判断目标是否已存在，避免发注定失败的命令。验证动作建议用查询读回或原版 `execute if …` 反查。
- 玩家类命令目标用 `GameProfile.name()`：`give` / `experience` / `effect` 等「只允许玩家」的命令会拒绝 UUID 字面量（真机实测），而 `tp` 接受实体所以 UUID 反而能用——两边不统一会静默失败。实体的 `getName()` 是显示名，会被 `设置名牌` 改掉，不能用。
- 高层覆盖由单测 `every_method_has_a_host_handler` 锁住：规格表里的每个查询 / 动作都必须在 `McApi` / `McActions` / `NebulaMod` 的 switch 里有分支。

### 5.1 指令与界面

- **插件指令**：词库在 `[f]初始化` 里调 `$世界.注册指令 商店 0 shop$`（规范名 + 可选权限 + 别名），桥接层把动作带回宿主注册 Brigadier 节点；`[f]卸载` 里 `$世界.注销指令$` 移除。执行时按规范名匹配 `[指令]商店|shop` 词条（`|` / `,` 分隔别名），只跑命中的那一条；没有 `[指令]` 词条时返回 `ok=false`。payload：`玩家 / 玩家名 / 指令（规范名） / 参数 / 管理员`。
- **界面对象 + 点击闭包**：词库先 `$面:$世界.创建界面 玩家 行数 标题$` 拿到对象 id，`$世界.界面物品 面 槽位 …$` / `$世界.界面数据 面 …$` 只作用于该对象，最后 `$世界.打开界面 面 回调$` 把 `回调:[函数] … 结束` 定义的闭包（按值捕获局部变量）交给宿主。宿主点击时调 `clickGui`，Rust 直接执行该闭包，payload 作为 `%参数1%`；关窗 / 退出 / 卸载 / 销毁时 `closeGui` 释放。同一玩家只保留最后一个打开的界面对象。
- **界面**：统一用 `创建界面` 返回的对象 + `打开界面 面 回调` 闭包。
- **动作归属**：事件执行期间 `世界` 动作会自动带上 `"插件"` 字段（`CURRENT_PLUGIN`）；界面对象本身也记录归属插件，只有创建者能操作它。
- 界面的可选项：`标题`（屏幕大标题）、`音效`、`给物品` / `扣物品`、`取物品数`（查询）。

## 6. 线程模型

- 平台 API 只能在服务端主线程调用；事件分发与 `世界` 原语同步执行。
- 异步任务（`$异步$` / 文件 / 网络）在工作线程执行，结果投递宿主的任务队列，下一 tick 回主线程。
- 编译 / 热重载在工作线程完成，替换在主线程进行。
- Rust 不主动跨线程访问世界对象。

## 7. 宿主实现要点（NeoForge）

| 项 | 当前实现 |
| --- | --- |
| 宿主入口 | `@Mod` + 事件总线 |
| 生命周期事件 | `ServerStartedEvent` / `ServerTickEvent.Post` / `ServerStoppingEvent` |
| 事件 payload | 直接读 `ServerLevel` / `ServerPlayer` |
| `世界` 实现 | `net.minecraft.*`（Mojang 映射） |
| 线程调度 | `ServerTaskQueue` + tick |
| 映射文档 | [`../mod/docs/mc-api-map.md`](../mod/docs/mc-api-map.md) |

> JNI 契约与平台无关；曾评估的 Paper / Spigot 备选分支已移除，取舍见 [`../mod/docs/platform-choice.md`](../mod/docs/platform-choice.md)。

## 8. 实现进度

- [x] Rust crate `nebula_jni`：JNI 符号、句柄、引擎生命周期。
- [x] 词库扫描 / 编译 / 热重载（含 `/nebula debug` 指纹热重载）。
- [x] 事件分发与变量注入（payload 解析）、插件指令定向分发、界面点击闭包。
- [x] `世界` 注入类与宿主 `McApi` / `McActions` 回调协议（映射表全量落地）。
- [x] 取消标志回写、广播返回值透传。
- [x] Windows 构建与原生库打包（`mod/build.ps1`）。
- [ ] Linux `libnebula_jni.so` 打包与跨平台冒烟。

## 9. 一键回归自检

`mod/selftest/` 把前面几轮手工验证固化成脚本（Python 标准库 + Node/mineflayer）：

| 文件 | 作用 |
| --- | --- |
| `regress.py` | 编排：产物校验（dll 哈希 + 源码内容指纹 + 原生库清单 + dll 编译期内嵌指纹）→ 准备隔离服 → 启动 → 两个假玩家 → 词库发动作 → 原版命令反查 → 恢复 tick、RCON `stop` → 清理；`--keep` 保留 `.testserver`，`--verify-only` 只校验产物不启服，`--skip-verify` 跳过校验（不推荐） |
| `rcon.py` | 极简 RCON 客户端 |
| `bot.js` | mineflayer 假玩家（`node bot.js <名字> [silent]`） |
| `checks.json` | 反查断言清单（`execute if …` / `data get entity …`） |
| `wordlib/` | 回归自检词库（查询、动作、`[f]重载` 读回） |

运行：`python mod/selftest/regress.py`。首次会自动 `npm install mineflayer@4.39.0`。
当前断言 17 项，实测 17/17 PASS、宿主告警 0 条。

安全边界：测试服在 `.testserver`，用目录联接共享 `libraries` / `.jdk`，独立
`level-name`；清理时先 `rmdir` 摘掉联接再删目录，并校验线上 `libraries` / `.jdk` / `world`
完好，**绝不触碰 `server-1.21.11/world`**。升级 Minecraft / NeoForge 版本后先跑这条命令，再按需更新
`checks.json` 里的断言。

## 10. 一键打包（先 dll，后 jar）

`bridge/pack.ps1` 把「更新引擎后重新出包」固化成一条命令：

```powershell
powershell -ExecutionPolicy Bypass -File bridge/pack.ps1             # 打包 + 秒级指纹校验
powershell -ExecutionPolicy Bypass -File bridge/pack.ps1 -Regression  # 打包 + 17 项真机回归
```

顺序固定，**不能颠倒、也不能用 `-SkipRust`**：

1. `[1/2]` `cargo build --release` 用当前引擎 crate 重新链接 `nebula_jni.dll`；失败立即停止，绝不拿旧 dll 打 jar。
2. `[2/2]` 调 `mod/build.ps1` 走**全量分支**（内部再跑一次 cargo 是 no-op，目的是重写 `nebula_jni.manifest.json`，让原生库清单重新绑定到新 dll 哈希），完成 javac / 文档 / 打 jar / 部署到 `server-1.21.11/mods`。
3. 默认跑 `regress.py --verify-only`；加 `-Regression` 改跑完整隔离服回归。

**为什么需要它**：`build.ps1 -SkipRust` 信任 `target/release/nebula_jni.manifest.json`，而指纹链只覆盖 `bridge/src` + `Cargo.toml`；引擎 `crates/nebula` 是仓库外的 path 依赖，**不在链内**。引擎一更新却用 `-SkipRust`，旧 dll 会被静默打进 jar，jar manifest / dll 内嵌指纹 / `--verify-only` 全部照旧通过（假 PASS）。

脚本因此额外做三件事：

- 从 `Cargo.toml` 读引擎路径，计算引擎 crate（`Cargo.toml` / `build.rs` / `src/**/*.rs`）的 SHA256，与 `target/release/nebula_engine.json` 比对并打印 `unchanged` / `CHANGED`。这份记录把「哪个引擎树产出了哪个 dll」补进信任链，每次打包后重写。
- 若引擎指纹变了但 dll 字节没变，直接抛错停在第 1 步（提示 `cargo clean -p nebula`），不产出 jar。
- 部署前检查有没有 MC 服务端 java 进程（`win_args.txt` / `@libraries/net/neoforged` 特征），有就拦住——线上服开着时覆盖 `server-1.21.11/mods` 会因 ModLauncher 懒加载类崩服。已验证 IDE 的 java 进程不会误判。

参数：`-NoVerify` 跳过校验、`-Force` 绕过两个守卫（服务端进程 / stale dll）、`-RustTargetDir <path>` 自定义 cargo target 目录（同时透传给 `build.ps1`）。**脚本必须保持纯 ASCII**：PowerShell 5.1 按 GBK 读无 BOM 的 `.ps1`，中文字符串会吃掉引号导致 ParserError。

只改 Java、引擎没动时仍可用 `mod/build.ps1 -SkipRust`；只要引擎仓库有更新，一律走 `pack.ps1`。
