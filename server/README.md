# NeoForge 专用服（已安装）

本目录是 `bedrock/server/` 的对称位置，现在是**可直接启动的 NeoForge 21.1.265 专用服实例**（Minecraft 1.21.1），包含 `.jdk`、`libraries`、`mods`、`world`、`logs`、`config`、`nebula` 与启动脚本。

## 1. 布局

```text
<gameDir>/
├── mods/
│   └── nebula-neoforge-bridge-<version>.jar   ← 桥接 mod（构建产物）
├── config/
│   └── nebula-bridge/native/nebula_jni.dll   ← 原生库解压目录（无 toml 配置，见 ../mod/docs/neoforge.md 第 8.4 节）
├── nebula/                                   ← 词库根（默认）
│   ├── 示例/
│   │   ├── main.n
│   │   └── nebula.ini
│   └── <插件名>/
│       ├── main.n                            ← 入口
│       └── 其它.n                            ← 同目录顶层自动合并
└── （NeoForge / Minecraft 服务端自身的文件）
```

与基岩版的对应：

| 基岩版 | Java 版 |
| --- | --- |
| `bedrock/server/plugins/nebula-bridge/nebula_bridge.dll` | `<gameDir>/mods/nebula-neoforge-bridge-<version>.jar` |
| `bedrock/server/nebula/<插件名>/` | `<gameDir>/nebula/<插件名>/` |
| `/nebula load|reload|unload|list|自检` | 同名命令，Brigadier 实现 |

本目录的 `nebula/示例/` 是可复制的示例词库，`nebula/伤害显示/` 是伤害数字插件，`nebula/登录/` 是账号密码登录插件（见各自 `说明.md`）；部署到其它实例时整目录拷到 `<gameDir>/nebula/`。

## 2. 词库规则

与基岩版一致：

- 一个文件夹 = 一个词库；文件夹名即插件名。
- 入口 `main.n`；没有 `main.n` 时只允许目录内唯一一个 `.n`。
- 目录顶层全部 `.n` 自动合并为同一命名空间；不再支持单文件 `$引入`。
- `_` / `.` 开头的目录禁用，不扫描。
- 生命周期：`[f]初始化` / `[f]加载` / `[f]重载` / `[f]卸载` / `[f]自检`。
- `[f]` 是私有函数，`[G]` 是公开函数；`[G]` 只改可见性，不是全局变量。

完整的插件开发流程（语法、生命周期钩子、事件变量、自注册指令、配置 JSON、界面闭包与排错）
见 [`../mod/docs/plugin-guide.md`](../mod/docs/plugin-guide.md)。

## 3. 运维

- 改词库不用重启：`/nebula reload <插件名|--all>`；开发时 `/nebula debug <插件名|--all>` 按文件变化自动重载。
- 改 mod 或 Rust 原生库必须重启服务端（jar / dll 被进程占用）。
- 启动脚本 `run.bat` 使用本目录 `.jdk` 里的 JDK 21；`run.sh` 需要系统 Java 21。
- `/nebula` 权限：控制台始终允许，玩家要求 OP；具体判定按目标 NeoForge 版本 API。
- 详细设计见 [`../mod/docs/neoforge.md`](../mod/docs/neoforge.md)。
