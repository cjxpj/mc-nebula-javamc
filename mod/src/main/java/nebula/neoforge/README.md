# `nebula.neoforge` 包结构

12 个类全部已落地（Minecraft 1.21.11 / NeoForge 21.11.42）。整体说明见
[`mod/README.md`](../../../../../README.md)，设计与实现现状见
[`docs/neoforge.md`](../../../../../docs/neoforge.md)。

```text
nebula/neoforge/
├── NebulaMod.java       @Mod 入口：12 个事件监听、/nebula 与插件指令、payload 组装、executeActions
├── NativeBridge.java    JNI 声明 + 从 jar 解压原生库到 config/nebula-bridge/native/ 并 System.load
├── McApi.java           `世界` 查询回调：读世界 → 文本 / JSON（39 类分支）
├── McActions.java       `世界` 动作落地：原版命令 / 稳定 API（67 类分支）
├── McSupport.java       反射兜底、实体查找、物品与实体快照、TPS/MSPT 滑窗、环境变量
├── GuiManager.java      自定义 ChestMenu：界面对象 id、点击拦截、闭包分发、会话清理
├── FloatingText.java    RPG 飘字：text_display 实体 + 逐 tick 上浮 + 开服残留清理
├── InputUi.java         铁砧命名框当文本框（输入界面）
└── ClickChat.java       可点击聊天行（点击消息）
```

约定：

- 词库的加载 / 卸载 / 热重载都经 `NativeBridge`，Java 侧不解析 `.n`，也不实现业务逻辑。
- 所有 Minecraft API 调用都从服务端事件回调同步进入，**当前没有跨线程入口**；代码里也没有
  `isSameThread()` 断言，新增异步入口时要自己保证主线程约束。
- 事件 payload 只含基础类型与 JSON，不跨线程持有 Minecraft 对象。
- 包里没有客户端专属类；`neoforge.mods.toml` 的依赖都标 `side = "SERVER"`。
- `世界` 方法与宿主的逐项对应见 [`docs/mc-api-map.md`](../../../../../docs/mc-api-map.md)。
  该文件是**编译期契约**：增删方法要同步改文档表格、`bridge/src/lib.rs` 规格表、本包对应
  switch 分支，再跑 `cargo test --offline --lib`。

早期规划中的 `ServerLifecycle` / `EventBridge` / `ServerTaskQueue` / `NebulaCommand` 没有单独建类。
