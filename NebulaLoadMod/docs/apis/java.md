# `java` API · `$java.eval`

> 这是**逃生口**，不是日常写法。业务逻辑仍然写在 `.n` 词库里；只有当宿主没暴露那个能力、
> 或者情况紧急到来不及改 mod 重新构建时，才用它。

`$java.eval` 把一段 Java 源码交给宿主：编译成类 → 实例化 → 调用 `run(Ctx, String)` → 返回值原样回填给词库。
编译在**服务端进程内**完成，不需要重启、不需要重新打包、不需要玩家重连。

## 用途

| 场景 | 例子 |
| --- | --- |
| 应急补丁 | 某个功能必须立刻改行为，但不能停服重新构建 |
| 宿主没暴露 | 需要读一个 `世界` 没有对应方法的原版字段 |
| 词库表达力不够 | 需要 `try` / 复杂循环 / 跨调用保留的状态 |

## 签名

```
$java.eval <代码|文件>$
$java.eval <代码|文件> <参数>$
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `代码` | `String` | 以 `.java` 结尾时按**插件目录下的文件**读；否则整段当内联源码 |
| `参数` | `String` | 可选，原样传给 `run(ctx, arg)` |

**返回值**：片段 `run()` 的返回文本（`null` 当空串）。失败时返回以 `[java] ` 开头的错误文本，
**不抛异常**——词库可以继续跑，也能把错误 `$提示$` 出来。

## 两种写法

### 1. 只写函数体（短片段）

```
$java.eval "if (ctx.player(\"Steve\") == null) return \"离线\"; ctx.broadcast(\"在线\"); return \"\";"$
```

宿主补全成 `public final class SnipXXXX implements NebulaSnippet { public String run(Ctx ctx, String arg) { … } }`。
没写 `return` 时会自动补 `return "";`。

### 2. 写完整类（有字段、有状态）

```
$java.eval "hotfix/刷怪塔限流.java"$
```

```java
import nebula.neoforge.snippet.Ctx;
import nebula.neoforge.snippet.NebulaSnippet;

public class 刷怪塔限流 implements NebulaSnippet {
    private int refusals = 0;          // 状态住在对象里，跨调用保留

    @Override
    public String run(Ctx ctx, String arg) {
        if (++refusals > 200) {
            ctx.log("本对象已拒绝 " + refusals + " 次，考虑把逻辑并回词库");
        }
        return String.valueOf(refusals);
    }
}
```

不写 `implements NebulaSnippet` 也行，只要类里有 `public String run(Ctx, String)` 就被当入口。

> 内联多行代码不好写：词库一行一个 `$调用$`，跨行要用 `变量::` 存原文再 `%变量%` 传。
> 长度超过两三行就落成 `.java` 文件，别硬塞。

## 生命周期与缓存

缓存键是 **插件名 + 源码 SHA-256**：

- 源码不变 → 复用同一个对象，字段状态保留；
- 改一个字符 → 新键、重新编译、**新对象**，旧状态自动丢弃；
- `/nebula load <插件>` 或 `unload` → 该插件的片段全部释放（重编译等于重来）；
- 编译失败也缓存，避免每个 tick 重编一次把服务器拖垮；改源码即可重试；
- 上限 128 个，超出按最近最少使用淘汰；开服停机时全部清空。

## `Ctx` 有什么

### 对象通道（拿真正的 Minecraft 对象）

| 方法 | 返回 |
| --- | --- |
| `server()` | `MinecraftServer` |
| `world("minecraft:the_nether")` | `ServerLevel`（不存在返回 null） |
| `overworld()` | `ServerLevel` |
| `player("Steve")` | `ServerPlayer`（名或 UUID，不在线 null） |
| `entity("12345")` | `Entity`（数字 id / UUID / 名字，遍历全维度） |
| `players()` | `List<ServerPlayer>` |
| `entities("minecraft:overworld")` | `List<Entity>` |
| `block("minecraft:overworld", x, y, z)` | `BlockState` |
| `pos(x, y, z)` | `BlockPos` |
| `tick()` | 当前 tick |
| `gameTime()` | 主世界游戏时间 |

拿到对象之后就是普通 Java：`ctx.player("Steve").getInventory().add(...)`、`ctx.block(...).getBlock()` 随便调。

### 词库同款通道（不用记 112 个签名）

| 方法 | 说明 |
| --- | --- |
| `ctx.query("取玩家坐标", "玩家", name)` | 调 `世界` 查询，返回宿主给的文本（多为 JSON） |
| `ctx.act("设方块", "维度", dim, "x", 0, "y", 64, "z", 0, "方块", "minecraft:stone")` | 调 `世界` 动作，立即在主线程落地 |
| `ctx.run("say hi")` | 原版指令，不带斜杠；语法错误只记日志 |
| `ctx.broadcast("…")` / `ctx.tell(名, "…")` | 全服 / 单人消息 |
| `ctx.cancel()` | 取消当前事件，等价 `$世界.取消事件$`；见下方限制 |
| `ctx.cancelled()` | 本次片段是否调用过 `cancel()` |
| `ctx.log(…)` / `ctx.warn(…)` | 写 `logs/latest.log`，前缀带 `[java/插件名]` |

`query` / `act` 的键就是 `world-specs.json` 里的参数名，能力与词库**完全一致**——不存在
「词库干不了、Java 片段能干」的暗门。

### 取消事件

```
[事件]玩家聊天
$java.eval "if (arg.contains(\"广告\")) { ctx.cancel(); return \"拦下\"; } return \"\";" %消息%$
```

注意第二个实参：片段里的 `arg` 就是它，**不是**自动带上事件 payload。要用 `%玩家名%` / `%消息%`
这些得自己作为实参传进去（或写进代码字面量）。

`ctx.cancel()` 写的是桥接层本次分发的取消标志，和 `$世界.取消事件$` 共用同一个开关。
但**只有宿主真正接了这个标志的事件才会生效**：目前是 `玩家聊天` 与 `方块破坏`。
`每秒` / `实体受伤` / `实体治疗` / `玩家进入` 调了也只是一次空操作（返回 true 只代表标志写回成功）。

> `实体受伤` 挂的是 `LivingDamageEvent.Post`（伤害已经扣完），事后取消在语义上不成立；
> 要拦伤害得换成 pre 事件，那是另一套 payload，不做。

## 硬限制

| 限制 | 原因 |
| --- | --- |
| **加不了新内容** | 新物品 / 生物 / 创造页条目都要走注册表，必须两端同版本重启；应急补丁只能「改已有东西的行为」 |
| **必须 JDK 启动** | 用 `jdk.compiler` 现场编译；jlink 精简运行时（JRE）里没有 javac，会直接返回「不可用」 |
| **主线程同步** | 片段在世界 tick 内执行，别自己开线程碰世界状态 |
| **不参与加载期诊断** | 词库的 `UNDEFINED_FUNC` 那套查不到 Java 里的错，编译失败只会在**第一次执行**时暴露 |

最后一条是它和「加一个世界方法」的根本差别：后者错了在 `/nebula load` 时就报，
前者要等到某条分支真被执行。所以**确认能用之后，把逻辑搬回词库或宿主**，别让逃生口变成常驻实现。

## 排查

| 现象 | 看哪里 |
| --- | --- |
| 返回 `[java] 编译失败：第 N 行：…` | 错误文本已经带行号，直接改源码 |
| 返回 `[java] 当前 JVM 没有 javac…` | 服务端不是 JDK 启动，换 JDK 或放弃该片段 |
| 返回 `[java] 读取 xxx.java 失败` | 路径按**插件目录**解析，不是服务器根目录 |
| 行为没变 | 源码改了一个字符就换新对象——确认改的是正在用的那份；`/nebula load` 看日志有没有「编译成功」 |
