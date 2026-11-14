# Minecraft 1.21.11 专用服（运行期目录，不入库）

Minecraft 1.21.11 主线专用服实例，**当前唯一主服**；旧 1.21.1 现场已整体删除。本目录内容全部由安装器或开服生成，`.gitignore` 只保留这份说明。

- 目标：Minecraft **1.21.11** / NeoForge **21.11.42** / Java 21
- 编译与回归默认指向这里：`mod/build.ps1`、`mod/selftest/regress.py` 的默认 `-ServerDir` / `--server-dir`
- 游戏端口 **25567** / RCON **25577**（密码 `nebula1211`）；停服走 RCON `stop`，不要 taskkill
- `.jdk/jdk-*` 放一份**完整 JDK 21**（要带 `javac.exe`）：`build.ps1` 用它编译，`regress.py` 和 `run.bat` / `run.sh` 用它启服，避免 PATH 上的 Java 17 被误用

## 重建这个目录

`maven.neoforged.net` 在部分网络下 DNS 被污染、代理 CONNECT 502，安装器直接跑会失败。可走 BMCLAPI 的 Maven 镜像：

1. 下载安装器：`https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.11.42/neoforge-21.11.42-installer.jar`
2. 从安装器内的 `version.json` / `install_profile.json` 取出所有 `downloads.artifact.path`，逐个从
   `https://bmclapi2.bangbang93.com/maven/<path>`（缺失时回退 `https://repo1.maven.org/maven2/<path>`）
   下载到本目录的 `libraries/<path>`。
3. 运行 `java -jar neoforge-installer.jar --install-server . --offline`，安装器会重映射服务端 jar、
   合并 NeoForge 类并生成 `win_args.txt` / `unix_args.txt`。
4. `.jdk/` 下放一份完整 **JDK 21**（例如 `.jdk/jdk-21.0.12.1-win32-x86_64/`），目录名必须以
   `jdk-` 开头且带 `bin/javac.exe`；`build.ps1` / `regress.py` 会自动发现它。这里用自包含目录，
   不再用指向其它服的目录联接——目标目录一旦删除，联接悬空会让构建与回归都起不来。
5. 准备 `eula.txt` / `mods/` / `nebula/`（词库从版本库或备份复制，运行期数据不要复制）。

## Windows 上的 netty / log4j 报错

NeoForge 21.11.42 使用 log4j-core **2.25.2**。FML 的 `net/neoforged/fml/startup/log4j2.xml`
里 `DebugFile` appender 的 pattern 带 `%xEx`（扩展堆栈渲染），log4j 2.25.x 会
`Class.forName` 堆栈里的每个类；netty 在 Windows 上探测 kqueue / epoll 时打的那条
DEBUG 日志因此触发 `io.netty.channel.kqueue.Native` / `epoll.Native` 初始化失败，
被 log4j 报成 `An exception occurred processing Appender DebugFile`。服务器本身能正常
`Done`，但 stderr 会刷两条长堆栈。

FML 把 DebugFile 的级别做成了系统属性，在 `user_jvm_args.txt` 里加一行即可：

```
-Dforge.logging.debugFile.level=info
```

代价只是 `logs/debug.log` 不再记录 DEBUG 行，其它 appender 的 info 日志完全不受影响。
不要去改 `fancymodloader/loader-*.jar` 里的 log4j2.xml——安装器重跑会覆盖掉。

## 构建与回归

```powershell
powershell -ExecutionPolicy Bypass -File mod/build.ps1 -SkipRust   # 只改 Java 时
python mod/selftest/regress.py --verify-only                       # 秒级指纹校验
python mod/selftest/regress.py                                    # 真机回归
```

jar 会部署到本目录的 `mods/`；回归用独立测试服 `.testserver-1.21.11`，不会碰本目录的 `world/`。
升级 NeoForge 后还要重跑 `python mod/tools/gen_events.py` 重新抽取事件清单（当前 **267** 个事件类，
来自 `neoforge-21.11.42-universal.jar`），再全量构建——清单来自旧版 jar 时通用事件桥注册的类名会和运行期对不上。
