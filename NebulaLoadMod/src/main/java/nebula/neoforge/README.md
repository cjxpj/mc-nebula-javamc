# `nebula.neoforge` 包结构

整体说明见 [`NebulaLoadMod/README.md`](../../../../../README.md)，设计与实现现状见
[`docs/neoforge.md`](../../../../../docs/neoforge.md)。

## 分工（硬约束）

mod 只做四件事：生命周期、事件转发、`世界` 注入回调的落地、注册表冻结前必须编译期登记的
容器型代码（`EntityType` / `Block` / 渲染器）。**玩法、物品、外观、交互、指令、进服表现
全部由 `<gameDir>/nebula/<插件>/` 里的 `.n` + `资源/` + `创造物品.json` 实现**，
改玩法不用重编 jar、不用重启客户端。

## 主要类

| 类 | 职责 |
| --- | --- |
| `NebulaMod` | `@Mod` 入口：事件监听、`/nebula` 与插件指令、payload 组装、`executeActions` 动作落地 |
| `NativeBridge` | JNI 声明与原生库解压加载 |
| `McApi` / `McActions` / `McSupport` | `世界` 查询回调 / 动作落地 / 通用支撑 |
| `EventBridge` | 按词库订阅动态注册 NeoForge 事件监听 |
| `NebulaPack` | 收集各插件 `资源/` 生成资源包（内建 `pack.mcmeta`）并 HTTP 投送 |
| `GuiManager` / `InputUi` / `DialogUi` / `ClickChat` / `FloatingText` | 词库动作对应的界面与表现能力 |
| `DropStack` / `DropLabel` | 掉落物堆叠与数量名牌（由词库动作驱动） |
| `CreativeItems` | 读各插件 `创造物品.json` 生成创造页条目 |
| `NebulaModDistributor` / `DocsSite` | mod 自发布 / 离线文档站点 |
| `content/NebulaEntities` / `NebulaBlocks` / `ChestCreature` / `Containers` | 词库插件在用的容器型注册项，不含玩法内容 |
| `src/client/java/...` | 实体渲染器、创造页客户端声明、版本提示 |

约定：

- Java 侧不解析 `.n`，也**不写死任何玩法文案 / 粒子 / 进服行为**；欢迎语、动作栏、粒子
  由词库插件 `[事件]玩家进入` 决定（见 `nebula/进服/main.n`）。
- 所有 Minecraft API 调用都从服务端事件回调同步进入；事件 payload 只含基础类型与 JSON，
  不跨线程持有 Minecraft 对象。
- `世界` 方法与宿主的逐项对应见 [`docs/mc-api-map.md`](../../../../../docs/mc-api-map.md)。
  它是**编译期契约**：增删方法要同步改文档表格、`world-specs.json`、宿主分支并跑 `cargo test`。
