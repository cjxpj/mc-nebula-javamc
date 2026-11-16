# 星云 × Minecraft Java 版

把星云词库引擎（`.n` 脚本）接到 Minecraft Java 服务端的 NeoForge 桥接层。

**一句话**：mod 只提供引擎原语，玩法逻辑全部写在 `.n` 词库文件里 —— 加一只怪、改一个物品、写一套界面，都不用重编 jar、不用重启服务端。

```text
           ┌──────────── 玩家客户端 ────────────┐
           │  同一个 jar（两端必须同版本）        │
           └───────────────┬───────────────────┘
                           │ 资源包（模型/贴图/语言/客户端声明）
┌──────────────────────────┴───────────────────────────┐
│ NeoForge 服务端                                       │
│  ├─ NebulaLoadMod   宿主：注册项、事件桥、渲染、网络    │
│  ├─ bridge          Rust cdylib（JNI）：词库引擎 + JIT  │
│  └─ nebula/<插件>/  ← 你写的地方：main.n + 资源/       │
└──────────────────────────────────────────────────────┘
```

## 能做什么

| 领域 | 词库能声明的 |
|---|---|
| 生物 | 属性、容器、碰撞箱、体/盖造型盒子、逐面 UV、多张贴图、8 个动画量、AI 目标、右键交互、死亡掉落、刷怪蛋 |
| 物品 | 基底、模型、贴图、堆叠、颜色、稀有度、耐久、描述、属性修饰符、创造页 |
| 世界 | 133 个 `世界` 方法（方块 / 实体 / 粒子 / 音效 / 容器 / 传送 / MOTD / 图标 / 指令 / GUI / 悬浮字） |
| 事件 | 6 个内置中文事件 + 267 条 NeoForge 事件（中英双键） |
| 其他 | 皮肤、列表 MOTD 与图标、资源包、mod 自发布与更新提示 |

设计边界（哪些归词库、哪些归引擎）写在 [`docs/能力边界.md`](docs/能力边界.md)。

## 兼容性

| 项 | 版本 |
|---|---|
| Minecraft | 1.21.11 |
| NeoForge | `[21.11, 21.12)`（开发用 21.11.42） |
| JDK | 21 |

两端必须装**同一个 jar**：服务端注册了动态生物实体与外观方块，客户端拿旧 jar 会在配置阶段被 registry sync 拒绝。

## 安装

1. 从 [Releases](../../releases/latest) 下载 `nebula-neoforge-bridge-<版本>.jar`
2. 丢进**服务端** `mods/`；想看到生物外观与资源包，客户端 `mods/` 也放同一份
3. 启动服务端。首次启动会生成：
   - `nebula/` —— 词库目录（自带 15 个示例插件）
   - `nebula/_docs/` —— 离线文档，用浏览器打开 `index.html` 就能看
4. 端口：游戏 `25567`、资源包与自发布 HTTP `25568`（可在 `server.properties` 与 `config/nebula-bridge/pack.properties` 调整）

## 快速开始

`nebula/我的插件/main.n`：

```n
[f]初始化
怪:$世界.注册生物 myserver:boxy$
$怪.设 名称 小箱子$
$怪.设 体型 small$
$怪.设 容器格数 27$
$怪.外观 方块 nebula_bridge:mob_body_8 nebula_bridge:block/my_body -0.5 0 -0.5 0.45$
$怪.加物品 刷怪蛋 minecraft:clay_ball my_egg 小箱子蛋 nebula:item/my_egg nebula:item/my_egg 16 生成蛋 gold$
$怪.生效$

[事件]每秒
表:$怪.列表 64$
遍历:i,个=%表%
    $巡逻 %个.uuid%$
结束
```

把贴图放 `资源/方块材质/my_body.png`、`资源/物品材质/my_egg.png`，然后：

```text
/nebula load 我的插件
```

创造模式「生成蛋」页里就有蛋了。改完再 `load` 一次即生效。

> 完整语法与踩坑清单见 [`NebulaLoadMod/docs/plugin-guide.md`](NebulaLoadMod/docs/plugin-guide.md)。

## 目录

```text
.
├── bridge/                 Rust JNI 桥接层（词库引擎 + 世界方法表 + 生物/物品对象）
├── NebulaLoadMod/          宿主 mod（Java）＋ 文档 ＋ 回归脚本
│   ├── src/main/java/      服务端：注册项、事件桥、世界方法、资源包派生、自发布
│   ├── src/client/java/    客户端：渲染器、资源包声明读取、更新提示
│   ├── docs/               离线文档（会打进 jar 并铺到 nebula/_docs/）
│   └── selftest/           回归脚本（Python + mineflayer 假玩家）
├── docs/                   设计与边界文档
└── server-1.21.11/         专用服实例：词库示例（运行期数据不入库）
```

## 构建

```powershell
# 只改 Java / 词库 / 文档
powershell -File NebulaLoadMod/build.ps1

# 改了 bridge/src（引擎）
powershell -File bridge/pack.ps1
```

- `.ps1` 必须是纯 ASCII（PowerShell 5.1 按 GBK 读）
- 版本号唯一源：`NebulaLoadMod/src/main/resources/META-INF/neoforge.mods.toml`
- 客户端源集需要本机一份 `minecraft-client-patched` jar，路径写进 `NebulaLoadMod/client-jar.txt`（不入库）
- **服务端跑着的时候别构建**，覆盖正在被读的 jar 会崩服

回归：

```text
python NebulaLoadMod/selftest/regress.py                  # 全量（会起测试服）
python NebulaLoadMod/selftest/regress.py --verify-only     # 只校验产物与源码指纹
cd bridge && cargo test --offline --lib -- --test-threads=1
```

## 文档

| 文档 | 内容 |
|---|---|
| [`plugin-guide.md`](NebulaLoadMod/docs/plugin-guide.md) | 词库插件作者指南：语法、目录、坑、生物/物品/界面全流程 |
| [`mc-api-map.md`](NebulaLoadMod/docs/mc-api-map.md) | 133 个 `世界` 方法与 267 个事件的完整参数表 |
| [`能力边界.md`](docs/能力边界.md) | 哪些归词库、哪些归引擎、还剩什么没接管 |
| [`注册生物-设计与规格.md`](docs/注册生物-设计与规格.md) | 生物对象方法的内部规格 |
| `nebula/_docs/index.html` | 上面这些的离线站点版（开服即生成） |

## 许可

保留所有权利（All Rights Reserved）。源码公开供学习与参考；在服务器上使用请自行承担风险，
二次分发或商用请联系作者。
