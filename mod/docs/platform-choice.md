# NeoForge 与 Paper 的取舍

> 归档说明：Paper / Spigot 备选分支（原 `javamc/paper/`，含插件元信息与 Bukkit 事件映射）已于 2026-11-06 移除。本文保留当时的取舍论证与对比表，供日后重新评估参考；Bukkit 侧映射文档不再保留。

## 1. 结论

- **NeoForge 作为主方案**：与 Minecraft 服务端同进程，能拿到完整服务端对象（`MinecraftServer` / `ServerLevel` / `ServerPlayer` / `BlockState`），不依赖 Bukkit 抽象层。
- **Paper / Spigot 曾作为备选分支**：骨架与 Bukkit 映射文档现已删除，不再维护；以下对比保留作历史参考。
- **共用层**：词库语法、加载规则、生命周期、事件变量命名、Rust 引擎、JNI 契约两端共用，平台差异只落在“事件源 + `世界` 实现”。

## 2. 对比（历史参考）

| 维度 | NeoForge | Paper / Spigot |
| --- | --- | --- |
| 接入方式 | mod（`@Mod` + 事件总线） | 插件（`plugin.yml` + 监听器） |
| API 稳定性 | 跟随 MC 版本，映射随版本变动 | Bukkit API 跨版本较稳定 |
| 世界对象 | 直接拿 `ServerLevel` / `BlockState` / `Entity` | 经过 `World` / `Block` / `Entity` 包装 |
| 事件覆盖 | 更接近原生，但部分事件需要 Mixin（如物品栏点击） | Bukkit 事件更“产品化”，覆盖面广 |
| 部署 | 服务端装 NeoForge + mod jar | 服务端装 Paper + 插件 jar |
| 客户端 | 服务端专属 mod，客户端不必安装 | 纯服务端插件，客户端不必安装 |
| 性能 | 少一层包装，适合高频方块/实体操作 | 包装层有额外开销，但通常可接受 |
| 生态 | 模组生态，需处理与其他 mod 的兼容 | 插件生态，插件间隔离较好 |
| 构建 | NeoGradle / ModDevGradle，依赖 NeoForge 版本 | Maven / Gradle，依赖 Paper API |
| 升级成本 | 跟随 MC 大版本，Mojang 映射变动 | 跟随 Paper API，通常更平滑 |

## 3. 为什么以 NeoForge 为主

1. **引擎在同进程**：Rust `cdylib` 通过 JNI 直接拿服务端对象，不需要跨进程 IPC，事件延迟低。
2. **方块/实体操作完整**：`世界` 的高频原语（26 邻域扫描、批量填充、实体查询）可以直接走 `ServerLevel`，不受 Bukkit 包装限制。
3. **与基岩版结构同构**：都是“宿主事件总线 → 桥接层 → 词库”，只是宿主不同，词库几乎可以复用。
4. **服务端 mod 无客户端要求**：词库逻辑全在服务端，客户端不安装 mod 也能进服。

代价：版本耦合更紧、部分事件缺失（如物品栏点击）、需要处理与其他 mod 的 Mixin/事件顺序问题。

## 4. 原 Paper 骨架的处置（历史）

| 现有内容 | 当时处置 | 当时位置（已随分支删除） |
| --- | --- | --- |
| `javamc/bridge/README.md` 的 Bukkit 事件/API 映射 | **下沉**为 Paper 分支资料 | `javamc/paper/plugin/API-MAP.md` |
| 原 Paper 插件元信息 `plugin.yml` | **下沉** | `javamc/paper/plugin/plugin.yml` |
| `javamc/bridge/`（JNI 契约思路） | **保留并升级**为平台无关的共用桥接层 | `javamc/bridge/README.md` |
| 原共享示例词库 | **保留共用**，随 NeoForge 专用服存放 | `javamc/server/nebula/示例/` |
| `javamc/README.md` 的 Paper 架构 | **替换**为 NeoForge 主方案说明 | `javamc/README.md` |

`javamc/paper/` 曾标记为“备选分支 / 暂停实现”，不进入 NeoForge 评审范围；该目录及其中的 `README.md`、`plugin/API-MAP.md`、`plugin/plugin.yml`、`server/README.md` 现已删除。NeoForge 侧的 `世界` 映射以 [`mc-api-map.md`](mc-api-map.md) 为准。

## 5. 落地状态（2026-11-09 校准）

本文是归档记录，下面几条说明「当时的判断后来变成什么样」，实现细节以
[`neoforge.md`](neoforge.md) 为准。

- 主方案已在 Minecraft 1.21.1 / NeoForge 21.1.265 上跑通：`bridge/` 保持平台无关，
  `mod` 落地 127 个 `世界` 方法，1.21.1 真机自检与一键回归 18/18 PASS。
- 第 2 节对比表里「部分事件需要 Mixin（如物品栏点击）」的顾虑已消解：点击由 `GuiManager`
  的自定义 `ChestMenu#clicked` 拦截，**不需要字节码增强**；只有客户端本地的 `打开背包`
  仍是占位。
- 构建没有采用 ModDevGradle / NeoGradle，而是 `build.ps1` 直接 `cargo` + `javac` + `jar`。
- 若日后重新引入别的宿主，平台差异仍集中在「事件源 + `世界` 实现」，JNI 契约与
  `bridge/` 不用重写。

## 6. 已确认与待拍板

已确认：

- NeoForge 为唯一主方案，Paper 备选分支已移除。
- 服务端专属，词库不下发客户端。
- 共用 Rust 引擎、JNI 桥接、词库加载规则与事件变量命名。
- 平台差异集中在事件源与 `世界` 实现层（当前只有 NeoForge 一侧宿主）。

待拍板：

- 若未来重新引入 Paper 或其他宿主，是否抽出 `javamc/common` 的 `世界` 语义层（当前不建）。
