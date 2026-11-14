# 文档索引（mod/docs/）

> **写词库插件，从这里开始**：[`plugin-guide.md`](plugin-guide.md) —— 五分钟上手、语法速查、
> 生命周期钩子、事件变量、`世界` 原语、自注册指令、配置 JSON、界面闭包、完整示例与排错清单。
>
> **浏览器入口**：双击 [`index.html`](index.html)（离线单文件，默认打开插件开发指南），
> 内嵌同目录全部文档，带目录、章节筛选与文档间跳转，不需要联网或起本地服务器。
> 改完任意 `.md` 要手动同步 [`index.html`](index.html) 里对应段落（搜索同一句话即可定位）——
> 文档是纯静态页，没有生成脚本。

文档随 mod 工程走：**一份面向插件作者的开发指南 + 三份设计 / 实现文档**。2026-11-09 从
顶层 `design/` 迁到此处，让文档和它描述的工程待在一起。

| 文档 | 讲什么 | 什么时候看 |
| --- | --- | --- |
| [`plugin-guide.md`](plugin-guide.md) | 怎么开发一个 `.n` 插件：目录、语法、钩子、事件、指令、配置、界面、完整示例、排错 | 第一次写插件，或写的时候卡住 |
| [`neoforge.md`](neoforge.md) | 设计决策 + 实现现状：边界、架构、生命周期、事件桥、线程、构建、决策台账 | 想理解整条链路，或不确定某条设计落地没有、要改 mod |
| [`mc-api-map.md`](mc-api-map.md) | `世界` 128 个方法的逐项映射、参数规约、降级清单、1.21.11 真机实测结论 | 查 `$世界.…$` 的参数与返回值，或新增 / 删除方法 |
| [`platform-choice.md`](platform-choice.md) | NeoForge 与 Paper / Spigot 的历史取舍（归档） | 重新评估宿主选型时 |

### API 参考（`apis/`，按 LLSE 体例）

参考 [LegacyScriptEngine API 文档](https://docs.levimc.org/LegacyScriptEngine/zh/apis/) 的组织方式：
先约定数据类型与描述格式，再按类别分篇，每条 API 都写「用途 → 签名（可选参数 `[]`）→ 参数名:类型
→ 返回值/类型 → 实现 → 示例 → 提示」。

- [`apis/README.md`](apis/README.md)：数据类型、描述约定、分类导航、调试。
- [`apis/事件.md`](apis/事件.md)：6 个内置事件 + 通用事件桥的 payload 字段、取消与避坑。
- 分类手册：[方块](apis/方块.md) · [坐标](apis/坐标.md) · [玩家](apis/玩家.md) ·
  [世界与服务器](apis/世界与服务器.md) · [实体](apis/实体.md) ·
  [交互与事件](apis/交互与事件.md) · [指令与界面](apis/指令与界面.md)（共 **128 条**）。

分类手册按 `mc-api-map.md`（契约表）与 `bridge/src/lib.rs`（规格表）手工维护，增删方法时
三处一起改，最后跑 `cargo test --offline --lib` 让单测兜住不一致。

其它入口：[`../README.md`](../README.md)（mod 工程说明）、
[`../../bridge/README.md`](../../bridge/README.md)（JNI 契约、词库加载规则、调用链）、
[`../../README.md`](../../README.md)（项目总览）。

> 注意路径：从 `mod/docs/` 往上两级是仓库根 `javamc/`。

## 建议的阅读顺序

**插件作者**：`plugin-guide.md` 第 0 节五分钟上手 → 第 1/2 节语法与生命周期 → 第 9 节完整可运行示例
→ 第 10 节排错。之后按需查 `mc-api-map.md` 里某个方法的参数。

**改桥接 / 改 mod**：项目总览 → mod 工程 README → `neoforge.md` 第 1~4 节（边界、生命周期、
钩子）→ `mc-api-map.md` 第 0 节参数规约。要增删方法看该节末尾的「维护流程」。

两个最容易踩的点：`[f]初始化` **每次编译都跑**（`[f]加载` 只在首次插入时跑）；
`$世界.…$` 动作**延迟执行**，同一词条里改完立刻查会读到旧值。

## 现成的示例插件

| 插件 | 演示什么 |
| --- | --- |
| `示例` | 最小结构、生命周期、聊天事件（`server-1.21.11/nebula/示例/main.n`） |
| `伤害显示` | 事件 payload、`%对象.字段%`、条件分支、配置开关（[`说明.md`](../../server-1.21.11/nebula/伤害显示/说明.md)） |
| `连锁砍树` | 多文件拆分、`[函数]` 显式传参、遍历与数值运算 |
| `泥土商店` | 自注册指令、`%库目录%` 读 JSON、界面对象 + 点击闭包（[`说明.md`](../../server-1.21.11/nebula/泥土商店/说明.md)） |
| `selftest/wordlib` | `[f]重载` 读回、批量动作 |

## 事实来源优先级

代码 > 单测 > 文档。三者冲突时以代码为准，并顺手把文档校准回来：

- `bridge/src/lib.rs`：规格表、生命周期钩子、JNI 契约。
- `mod/src/main/java/nebula/neoforge/`：宿主实现（事件 payload 与方法分支）。
- `server-1.21.11/nebula/*/main.n`：真实可跑的词库写法，指南里的示例都从这些文件核对过。
- `cargo test --offline --lib`：文档 ↔ 函数、规格表 ↔ 宿主、参数位次三层一致性。
- `python mod/selftest/regress.py`：1.21.11 真机一键回归（19/19 PASS）；启服前按 jar 内 `META-INF/nebula-build.json` 的源码内容指纹校验（不看 mtime），与 `target/release/nebula_jni.manifest.json` 交叉校验，并读取 dll 内 `build.rs` 烧入的 `NEBULA_SOURCES=` 编译期指纹作为终点证据，`--verify-only` 可秒级检查。用旧 jar 跑出的 PASS 不算数。

## 维护约定

- **HTML 入口（纯静态，无生成脚本）**：`index.html` 是手工维护的离线单文件阅读器，
  内嵌本目录全部 `.md`（`file://` 下 `fetch` 本地 md 会被浏览器拦截，所以只能内嵌）。
  默认落地页是插件开发指南。**改了 `.md` 就要同步改 `index.html` 里那段内嵌文本**，
  否则页面与源文件各说一套——这是删掉生成脚本后唯一的代价，页脚时间戳同样手写。
  判断有没有漏：`grep` 改过的那句话，两边都应该命中。
- **随服分发的离线站点**：`build.ps1` 把本目录的 `.md` 和 `index.html` 打进 jar 的 `docs/`，
  同时写 `docs/version.txt`（内容取自
  `neoforge.mods.toml` 的 `version`，版本号只有这一个来源，jar 名也从它来）和
  `docs/.stamp` 内容指纹——版本号本身折进指纹，所以只改版本号也会触发 `_docs` 重建，
  不会留下一份写着旧版本的站点。开服时
  `DocsSite.ensure()` 把它们铺到 `<gameDir>/nebula/_docs/`：目录以下划线开头，
  引擎 `scan_plugins` 直接跳过，不会被当词库编译或执行，纯粹给人用浏览器打开看
  （自包含单文件，`file://` 双击即可，不需要起服务）。指纹一致就跳过，jar 里的文档
  变了才整目录重建；手动改 `_docs` 里的文件不会被自动纠正（当成本地改动），想回出厂
  内容就删掉 `_docs` 或它的 `.stamp`。改完 md 要先同步 `index.html` 再重建 jar，
  否则随服那份站点里的页面还是旧内容（`regress.py` 会按内容指纹把这种滞后报出来）。
- **`mc-api-map.md` 是编译期契约**：`bridge/src/lib.rs` 用
  `include_str!("../../mod/docs/mc-api-map.md")` 把它读进单测。再挪路径要同步改
  这行 `include_str!`、各级 README 链接和 Java 注释里的路径，然后跑一次 `cargo test`。
- 增删 `世界` 方法要同时改 4 处（文档表格 / 规格表 / 宿主分支 / 测试），步骤写在
  `mc-api-map.md` 的「维护流程」。
- 改了词库语法或生命周期语义，`plugin-guide.md` 也要跟着改——它是对外的第一入口，别只更新
  `neoforge.md`。
- 设计决策变化记进 `neoforge.md` 第 9 节决策台账；作废的早期设计标成「已废弃」并说明被什么取代。
- `platform-choice.md` 是归档，描述当时的判断，不随实现更新。
