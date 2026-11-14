# 星云 × Minecraft Java 版（NeoForge 为主）

把星云词库引擎（`.n` 词库）挂到 Minecraft Java 版服务端。本目录与 [`bedrock/`](../bedrock/bridge/README.md) 对称：`bridge/` 是平台无关的 Rust 桥接层，`mod/` 是 NeoForge mod 工程（源码 + 文档），`server-1.21.11/` 是专用服实例（运行期数据）；三者平级，源码与运行期不混放。

> 状态：NeoForge 是唯一主方案，已在 Minecraft 1.21.11 / NeoForge 21.11.42 上落地；曾评估的 Paper / Spigot 备选分支已移除，取舍记录见 [`mod/docs/platform-choice.md`](mod/docs/platform-choice.md)。

## 1. 结论先行

- **主方案**：NeoForge mod（服务端）+ Rust `cdylib`（JNI）+ 星云词库。
- **注入对象**：词库统一用 `$世界.方法 …$` 调用。
- **服务端专属**：词库只在服务端主线程运行，不下发到客户端，不需要客户端安装 mod。
- **词库目录**：`<gameDir>/nebula/<插件名>/`，与 `bedrock/server-1.21.11/nebula/` 同构（当前固定，不可配置）。
- **离线文档站点**：`build.ps1` 把 `mod/docs` 的 `.md` + `index.html` 打进 jar，开服时铺到 `<gameDir>/nebula/_docs/`（下划线前缀，引擎 `scan_plugins` 跳过，不当词库编译）；纯静态单文件，浏览器双击 `index.html` 就能看，不需要起服务。同目录 `version.txt` 记录生成它的模组版本（取自 `neoforge.mods.toml`，版本一改下次开服必整体刷新）。
- **插件指令**：词库在 `[f]初始化` 里 `$世界.注册指令 商店 0 shop 泥土商店$` 自注册，`[f]卸载` 里 `$世界.注销指令$` 移除；执行时按规范名匹配 `[指令]商店|shop` 词条，只跑命中的那一条（没有 `[指令]` 词条时返回 `ok=false`）。
- **可视化**：`$面:$世界.创建界面 玩家 行数 标题$` 返回界面对象，之后 `$世界.界面物品 面 …$` 等只操作这个对象；`$世界.打开界面 面 回调$` 把 `回调:[函数] … 结束` 闭包交给宿主，点击时直接执行闭包（payload 作为 `%参数1%`）。界面统一用「对象 + 点击闭包」。
- **`世界` 方法**：按 [`mod/docs/mc-api-map.md`](mod/docs/mc-api-map.md) 全量落地 128 个（方块 / 坐标 / 玩家 / 世界 / 实体，含查询与动作），唯一占位是 `打开背包`；参数位次、降级清单与真机实测结论见该文档第 0 节。
- **示例**：`server-1.21.11/nebula/` 下有 `示例` / `伤害显示` / `连锁砍树` / `泥土商店`（泥土计价、买/卖双页、确认弹窗）/ `登录`（原版 dialog 弹窗输入密码，两次设置 + 冻结 + 300 秒超时踢人）。
- **共用资产**：`javamc/bridge/`（JNI 契约）、`javamc/server-1.21.11/nebula/示例/`（示例词库）。

## 2. 与基岩版的对应关系

| 层 | 基岩版（`bedrock/`） | Java 版（`javamc/`） |
| --- | --- | --- |
| 服务端宿主 | BDS + LeviLamina | NeoForge（Minecraft Java 服务端） |
| 桥接层 | `bedrock/bridge`（Pier C ABI + Rust cdylib） | `javamc/bridge`（JNI + Rust cdylib） |
| 平台 mod / 插件 | LeviLamina 插件 | `javamc/mod`（NeoForge mod） |
| 词库目录 | `bedrock/server-1.21.11/nebula/<插件名>/` | `javamc/server-1.21.11/nebula/<插件名>/` |
| 生命周期 | `[f]初始化` / 加载 / 重载 / 卸载 | 同左，但语义有差别（见设计文档第 4.2 节） |
| `世界` 原语 | Rust 直接调 BDS API | Rust → JNI → NeoForge / Minecraft API |
| 事件源 | LeviLamina 事件总线 | NeoForge 事件总线 |

## 3. 目录

```text
javamc/
├── README.md                         ← 本文：总览与结论
├── bridge/                           ← Rust JNI 桥接层（与平台无关）
│   └── README.md
├── mod/                              ← mod 工程：只有源码 / 文档 / 构建脚本
│   ├── README.md
│   ├── docs/                         ← 文档（随 mod 工程走）
│   │   ├── plugin-guide.md           ← 词库插件开发指南（写插件先看这个）
│   │   ├── apis/                     ← LLSE 体例的 API 手册（128 条，手写维护）
│   │   ├── index.html                ← 浏览器入口（离线单文件，纯静态、无生成脚本）
│   │   ├── README.md                 ← 索引、事实来源与维护约定
│   │   ├── neoforge.md               ← 设计决策 + 实现现状
│   │   ├── mc-api-map.md             ← `世界` 方法 ↔ API 映射（编译期契约）
│   │   └── platform-choice.md        ← 平台取舍记录（归档）
│   ├── build.ps1 / gradle.properties
│   ├── selftest/                     ← 一键回归自检
│   └── src/main/...
└── server-1.21.11/                           ← NeoForge 专用服（运行期，已安装 21.11.42）
    ├── README.md
    ├── mods/                         ← mod 构建产物部署到这里
    ├── nebula/示例/{main.n, nebula.ini}
    ├── world/ logs/ config/ ...
    └── run.bat / run.sh / user_jvm_args.txt
```

`mod/` 与 `server-1.21.11/` 不共用目录：`mod/build.ps1` 把 jar 部署到 `server-1.21.11/mods/`，词库根固定在 `server-1.21.11/nebula/`；`server-1.21.11/` 只放运行期数据与随服词库，不放源码。

## 4. 评审入口

**写词库插件先看 [`mod/docs/plugin-guide.md`](mod/docs/plugin-guide.md)**：从建目录、语法、生命周期钩子，到指令、配置 JSON、界面闭包，含一个完整可运行示例和排错清单。

三份设计文档都在 `mod/docs/`，索引与维护约定见 [`mod/docs/README.md`](mod/docs/README.md)。浏览器入口是 [`mod/docs/index.html`](mod/docs/index.html)（离线单文件，默认打开插件开发指南，带目录与筛选）。

1. 写插件：[`mod/docs/plugin-guide.md`](mod/docs/plugin-guide.md) —— 语法、钩子、事件、指令、配置、界面、完整示例与排错。
2. 查方法：[`mod/docs/apis/README.md`](mod/docs/apis/README.md) —— 按 LLSE 体例组织的 128 条 API 手册（签名 / 参数类型 / 返回值 / 示例），按类别分篇；契约表与真机实测结论在 [`mc-api-map.md`](mod/docs/mc-api-map.md)。
3. 改桥接 / 改 mod：[`mod/docs/neoforge.md`](mod/docs/neoforge.md) —— 边界、生命周期、事件桥、线程、构建与决策台账；每条都标了「已落地 / 规划 / 已废弃」。
4. 宿主选型归档：[`mod/docs/platform-choice.md`](mod/docs/platform-choice.md) —— 为什么以 NeoForge 为主（原 Paper 备选分支已移除）。
5. 目录骨架：[`bridge/`](bridge/README.md)（Rust JNI）、[`mod/`](mod/README.md)（mod 源码 + 文档 + `selftest/` 一键回归）、[`server-1.21.11/`](server-1.21.11/README.md)（专用服运行期）。

## 5. 决策台账（详见 `mod/docs/neoforge.md` 第 9 节）

**已定**：Minecraft 1.21.11 / NeoForge 21.11.42 / Java 21；构建用 `build.ps1` + `javac`，不引入 Gradle；词库根固定 `<gameDir>/nebula`；只支持专用服务端；界面点击用自定义 `ChestMenu`，不引入 Mixin；原生库 jar 内打包、运行时解压；插件指令由词库自注册。

**仍开放**：Linux / macOS 原生库打包、`nebula-bridge.toml` 配置化、规划中的事件转发、`$异步$` 结果回主线程、集成服务端支持。

## 6. 常用命令

```powershell
# 一键打包（更新引擎后必走）：先重链 dll → 再打 jar → 指纹校验；加 -Regression 顺带跑真机回归
powershell -ExecutionPolicy Bypass -File bridge/pack.ps1

# 全量构建（Rust release + javac + jar → server-1.21.11/mods），只改 Java、引擎没动时加 -SkipRust
powershell -ExecutionPolicy Bypass -File mod/build.ps1

# Rust 单测：文档 ↔ 函数 / 规格表 ↔ 宿主 / 参数位次 / 随服词库编译，7 条
cd bridge; cargo test --offline --lib

# 1.21.11 真机一键回归：隔离服 + 双假玩家 + 原版命令反查，19/19 PASS
python mod/selftest/regress.py

# 只校验产物与源码是否一致（秒级，不启服）
python mod/selftest/regress.py --verify-only

# 改完映射文档 / 规格表后：手工同步 apis/*.md 与 index.html（无生成脚本），再跑单测
cargo test --offline --lib
```

改词库用 `/nebula reload <插件>`（或 `/nebula debug` 自动重编译），改 mod / 桥接需重启；主服游戏端口 25567 / RCON 25577，停服走 `stop`，不要用 taskkill。

## 7. 独立仓库的构建前置

本仓库只含 `javamc/`，是从原来的 `mc-nebula/`（同层另有 `bedrock/`）单独拆出来发布的，因此保留了两处旧布局的痕迹：

- **引擎 crate 是仓库外依赖**：`bridge/Cargo.toml` 写的是 `nebula = { path = "../../../rust-nebula/crates/nebula", features = ["jit"] }`，指向外部的 `rust-nebula` 引擎仓库（目前为私有，且 `jit` 特性需要 rustc 1.96+）。clone 本仓库后，需把引擎放到相对路径 `../../../rust-nebula/`，或把 `path` 改成本地实际位置，否则 `cargo build` 会直接失败。
- **少数相对链接指向上层目录**：如开头与表格中的 `../bedrock/bridge/README.md`，原意是同级的基岩版实现，单独 clone 后该路径不存在，按名称到对应仓库查阅即可。

**运行期数据不入库**（规则见 `.gitignore`）：`server-1.21.11/libraries/`、`.jdk/`、`world/`、`config/`、`mods/`、`logs/`、`crash-reports/` 都由安装器或开服生成；`server-1.21.11/nebula/_docs/` 是开服时从 `mod/docs/` 铺出的文档副本；`server-1.21.11/nebula/*/data/` 含登录插件的账号哈希、盐与会话，属于运行期数据。要在新机器复现专用服，需自行运行 NeoForge 21.11.42 安装器，再参考 `server-1.21.11/server.properties`、`run.bat`、`run.sh` 启动。

**产物与版本对应关系**：

- 当前主线是 `nebula-neoforge-bridge-0.2.0.jar`，针对 **Minecraft 1.21.11 / NeoForge 21.11.42 / Java 21 专用服务端**编译，由 `mod/build.ps1` 部署到 `server-1.21.11/mods/`（jar 内含 Windows x86_64 的 JNI 库与离线文档站）。
- GitHub Release `v0.1.0` 附带的 `nebula-neoforge-bridge-0.1.0.jar` 是**旧版 1.21.1 / NeoForge 21.1.265** 的产物，与 1.21.11 不兼容（命令权限系统对象化、`ResourceLocation` 更名 `Identifier`、实体 / 容器 API 重构等），不要混用。
- 1.21.11 主线源码与 0.2.0 产物尚未打新 tag / 发布新 Release。
