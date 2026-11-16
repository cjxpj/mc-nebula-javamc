# NeoForge mod 工程

`nebula-neoforge-bridge` 是 NeoForge mod（`side=BOTH`，客户端只提供实体渲染器与版本提示）：**只负责生命周期、事件转发、命令注册与 `世界` 注入回调**，以及注册表冻结前必须编译期登记的容器型代码（实体类型 / 方块 / 渲染器）。玩法逻辑、物品、外观、交互、指令、进服表现全部由 `<gameDir>/nebula/<插件>/` 的 `.n` + `资源/` + `定义/*.json` 实现，改玩法不用重编 jar。设计与实现现状见 [`docs/neoforge.md`](docs/neoforge.md)，三份设计文档的分工见 [`docs/README.md`](docs/README.md)。

> **要写词库插件**：看 [`docs/plugin-guide.md`](docs/plugin-guide.md)，从建目录到指令、配置、
> 界面闭包与排错，含完整可运行示例。HTML 入口默认打开的也是这一份。

> 浏览器入口：[`docs/index.html`](docs/index.html) —— 离线单文件，内嵌全部文档，双击即可看。
> 纯静态、没有生成脚本：改完 `.md` 要同步改 `index.html` 里对应段落。

## 1. 目录

```text
NebulaLoadMod/
├── README.md                                  ← 本文
├── docs/                                      ← 文档（与 mod 源码同一工程）
│   ├── plugin-guide.md                        ← 词库插件开发指南（主入口）
│   ├── apis/                                  ← LLSE 体例的 API 手册（128 条，手写维护）
│   ├── index.html                             ← 浏览器入口（离线单文件，纯静态无脚本）
│   ├── README.md                              ← 索引、事实来源与维护约定
│   ├── neoforge.md                            ← 设计决策 + 实现现状
│   ├── mc-api-map.md                          ← `世界` 方法 ↔ API 映射（编译期契约）
│   └── platform-choice.md                     ← 平台取舍记录（归档）
├── gradle.properties                          ← 版本占位（未引入 Gradle，仅留档）
├── build.ps1                                  ← Rust + javac + 打包部署
├── selftest/                                  ← 一键回归自检
└── src/main/
    ├── resources/META-INF/neoforge.mods.toml  ← mod 元信息
    └── java/nebula/neoforge/
        └── README.md                          ← 实际类结构
```

> `docs/mc-api-map.md` 不只是说明文档：Rust 桥接层用 `include_str!` 把它编进单测，逐条断言「文档里的方法都有函数、函数都写进了文档」。增删方法要同时动文档表格、world-specs.json、宿主分支并跑测试，步骤见该文档第 0 节末尾。

`build.ps1`：Rust 原生库由 `../bridge` 构建，Java 类、`META-INF` 与原生库一起打进
`build/nebula-neoforge-bridge-0.2.0.jar` 并复制到 `../server-1.21.11/mods/`。**没有 Gradle 工程**。

## 2. 类结构（已落地）

| 类 | 职责 |
| --- | --- |
| `NebulaMod` | `@Mod` 入口：注册 12 个事件监听、`/nebula` 与插件指令、payload 组装、`executeActions` 动作落地 |
| `NativeBridge` | JNI 声明与原生库解压加载（`create` / `destroy` / `dispatch` / `dispatchCommand` / `clickGui` / `closeGui` …） |
| `McApi` | `世界` 查询回调：方块 / 坐标 / 玩家 / 世界 / 实体快照共 39 个查询分支 |
| `McActions` | `世界` 动作落地：67 个动作走原版命令或稳定 API（方块 / 玩家 / 世界 / 实体） |
| `McSupport` | 通用支撑：反射兜底、实体查找、物品与实体快照、TPS/MSPT 40 tick 滑窗、环境变量 |
| `GuiManager` | 词库驱动的箱子界面：会话、物品渲染、界面对象 id 绑定、点击拦截与回调分发 |
| `FloatingText` | RPG 飘字：按 `/summon` 的 NBT 建 `text_display`，逐 tick 上浮，到时 `discard`；带 `nebula_fx` 标签，每秒扫一次残留（区块重载 / 崩服留下的） |

早期规划里的 `ServerLifecycle` / `ServerTaskQueue` / `NebulaCommand` 未单独建类：生命周期与
命令注册集中在 `NebulaMod`；`EventBridge` 已单独落地并按插件订阅动态注册监听器；
`ServerTaskQueue` 仍是规划项。上表只列核心类，完整清单见 `src/main/java/nebula/neoforge/README.md`。详见 [`docs/neoforge.md`](docs/neoforge.md) 第 2、3.4 节。

## 3. 生命周期约定

- mod 构造函数只注册事件监听，**不读配置、不加载原生库、不创建引擎**。
- `ServerStartedEvent`：`NativeBridge.load()` → `create(<gameDir>/nebula)` → `loadAll()` →
  `executeActions`（落地插件 `[f]初始化` 里注册的 Brigadier 指令节点）。
- 每 tick 的 `ServerTickEvent.Post` 调 `McSupport` 统计 TPS / MSPT / 服务器 tick，并推进
  `FloatingText` 的上浮；两件事都**不调用 JNI**。
- `RegisterCommandsEvent` 注册 `/nebula list|load|reload|debug|undebug|unload` 并清空插件指令映射；
  插件指令随 `[f]初始化` 注册、`[f]卸载` 注销，之后 `sendCommands` 重发命令树。
- 界面点击由 `GuiManager.NebulaMenu#clicked` 拦截，按菜单绑定的界面对象 id 调 `clickGui`
  执行 Rust 侧闭包；缺少界面对象 id 的点击会被忽略并记 WARN。
  关窗 / 退出 / 卸载时 `closeGui` 释放闭包。
- `ServerStoppingEvent`：清理界面与指令映射、`NativeBridge.destroy(handle)`。
  **注意 `destroy` 不执行 `[f]卸载`**，只有 `/nebula unload` 会跑卸载钩子。

## 4. 构建与版本

见 [`docs/neoforge.md`](docs/neoforge.md) 第 8 节。

- 版本已锁定：Minecraft 1.21.11 / NeoForge 21.11.42 / Java 21（服务端自带 `.jdk/jdk-*`）。
- `build.ps1` 四步：`cargo build --release` → `javac`（classpath 取 `../server-1.21.11/libraries`
  下除 `-slim` / `-unpacked` 外的官方映射 jar）→ 复制 `neoforge.mods.toml` 与 dll →
  `jar --create` 并部署。只改 Java 用 `-SkipRust`，`-RustTargetDir` 可指定 Rust 产物目录。
  **引擎 `crates/nebula` 不在指纹链内**：引擎有更新时改用 [`../bridge/pack.ps1`](../bridge/README.md)
  全量重链 dll，否则 `-SkipRust` 会把旧 dll 打进 jar 而所有校验照旧通过（假 PASS）。
  部署后还会比对 jar 内嵌 dll 与 `target/release/nebula_jni.dll` 的 SHA256，不一致直接失败；
  同时把 Rust / Java 源码逐文件 SHA256 写进 jar 内 `META-INF/nebula-build.json`，
  `-SkipRust` 不重编 dll，改读 `target/release/nebula_jni.manifest.json`（cargo 产出 dll 时落下的、
  与 dll 哈希绑定的一手清单）；清单缺失或 dll 哈希不符直接失败。
  `bridge/build.rs` 还会在编译时把源码指纹烧进 dll 自身（`NEBULA_SOURCES=` 标记），
  回归时直接读 dll 字节比对——不依赖任何事后文件的信任链终点。
- Rust 侧构建 `cdylib`，按平台目录打进 jar；运行时解压到
  `<gameDir>/config/nebula-bridge/native/` 后 `System.load`。当前构建脚本只打包 windows-x86_64。
- mod 依赖标 `side = "BOTH"`（客户端要装同一个 jar 才能渲染词库插件注册的实体）；**词库引擎只在服务端创建**，客户端不加载 `.n`。

## 5. 与运行期的关系

mod jar 部署到 `../server-1.21.11/mods/`；词库根**固定**为 `<gameDir>/nebula/`（见
[`../server-1.21.11/README.md`](../server-1.21.11/README.md)）。改词库不需要重启服务端，`/nebula load`
或 `/nebula debug` 重编译；改 mod / 桥接需重启。升级版本前先跑
`python selftest/regress.py`（隔离服 + 双假玩家 + 原版命令反查，19/19 PASS）。
