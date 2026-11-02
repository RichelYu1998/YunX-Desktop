---
name: "yunx-desktop"
description: "云析桌面版（YunX-Desktop）开发规范与技能手册：目录分层、Kotlin/Compose 代码规范、内嵌浏览器登录态采集约定、新增网盘接入清单、版本管理与版本记录。改代码、加平台、发版前必读。"
---

# 云析桌面版 · 开发规范与技能手册

> 本文件与 [README.md](README.md) 是本项目的**规范来源**：README 面向使用者，本文件面向开发者。
> Word 版 [skill.docx](skill.docx) 由本文件生成，**不要手改 docx**（改完本文件重新生成即可）。

## 1. 项目概览

- **形态**：Kotlin + Compose Multiplatform（Desktop）单窗口桌面应用，Windows 为正式分发平台，macOS / Linux 可开发调试。
- **主线**：粘贴分享链接 → 解析 → 浏览 → 预览 → 高速下载；网盘、视频、GitHub、P2P 四类链路共用一套下载与任务管理。
- **技术栈**：OkHttp 4.12、SQLite（`sqlite-jdbc`）、`java.util.prefs`、JCEF 132（内嵌 Chromium）、ffplay / JavaFX Media、JNA + `darkmode.dll`、Gradle + jlink + jpackage + Inno Setup。

## 2. 目录结构与分层

| 路径 | 职责 |
| --- | --- |
| `desktop/src/main/kotlin/com/yunx/app/data/network/` | 各平台协议封装：`XxxApi.kt` + `XxxConstants.kt` |
| `desktop/src/main/kotlin/com/yunx/app/data/repository/` | 业务编排：`XxxAccountRepository`（账号）/ `XxxResolveRepository`（解析与转存） |
| `desktop/src/main/kotlin/com/yunx/app/data/download/` | 下载引擎：任务调度、分片、断点续传、落盘 |
| `desktop/src/main/kotlin/com/yunx/app/data/security/` | 凭证加密（`DbKeyStore`）与机器指纹 |
| `desktop/src/main/kotlin/com/yunx/app/data/bridge/` | 浏览器插件本地回环桥（仅 `127.0.0.1`） |
| `desktop/src/main/kotlin/com/yunx/app/ui/login/` | 各平台登录页（`XxxLoginScreen`），优先内嵌浏览器、兜底粘贴 |
| `desktop/src/main/kotlin/com/yunx/app/ui/jcef/` | 内嵌浏览器：`JcefHolder`（全局初始化）/ `JcefLoginPane`（通用登录面板） |
| `desktop/src/main/kotlin/com/yunx/app/ui/screens/` | 主界面页面与弹窗 |
| `desktop/src/main/kotlin/com/yunx/app/ui/viewmodel/` | 页面状态与协程编排 |
| `desktop/src/main/kotlin/com/yunx/app/util/` | 日志、剪贴板、Cookie 导入、单实例锁等工具 |
| `desktop/native/` | 原生桥接源码（`darkmode.cpp` → `darkmode.dll`） |
| `launcher/`、`installer/`、`docs/` | 启动器、安装脚本、对外文档 |

**依赖方向**：`ui` → `viewmodel` → `repository` → `network` / `download` / `security`。
`network` 层不得反向依赖 `ui` 或 `repository`；跨层共用常量放对应平台的 `XxxConstants.kt`。

## 3. 代码规范（Kotlin / Compose）

### 3.1 命名

- 类 / 文件：`PascalCase`，文件名与主类型同名；平台相关统一 `XxxApi`、`XxxConstants`、`XxxRepository`、`XxxLoginScreen`。
- 函数 / 属性：`camelCase`；布尔用 `is` / `has` / `can` 前缀；常量 `UPPER_SNAKE_CASE`。
- 日志 TAG：每个类用 `private companion object { const val TAG = "YunX-<模块>" }`，不要散落字符串字面量。
- 文件以换行符结尾，行尾不留空格；缩进 4 空格，不用 Tab。

### 3.2 注释

- **注释写「为什么」，不写「是什么」**：协议里容易踩坑的字段、魔数、风控/降级原因必须写明（例如「缺 `PANWEB` 时网盘接口一律当未登录」）。
- 公开 API、复杂分支、兜底逻辑必须有 KDoc；一眼能看懂的赋值、`return` 不加注释。
- 注释语言统一中文；引用类型用 `[Type]` 链接；`★` 用于标记「改动这里会踩坑」的关键约束。

### 3.3 日志

- 统一用 `com.yunx.app.util.Log`（与 `android.util.Log` 同签名：`i` / `d` / `w` / `e`），**禁止** `println` 与 `System.out`。
- 网络层失败必须打日志并带上可定位字段：HTTP 状态码、`errno`、关键参数是否为空（如 `bdstoken=空`）。
- 异常日志用 `Log.e(TAG, msg, throwable)`，不要把异常只塞进消息字符串。

### 3.4 协程与线程

- 网络 / 磁盘一律 `withContext(Dispatchers.IO)`；Compose 侧用 `LaunchedEffect` / `rememberCoroutineScope`，禁止裸线程。
- `try/catch` 里遇到 `CancellationException` **必须重新抛出**，不得吞掉取消。
- 轮询类逻辑（如登录态检测）抽成常量间隔（`POLL_INTERVAL_MS`），失败重试与轮询间隔分离。

### 3.5 网络请求

- OkHttp 单例 `client`，`Request` 必带平台要求的 `User-Agent` / `Referer` / `Cookie`（不同接口的 UA 用常量区分，如 `UA_WEB` / `UA_NETDISK`）。
- 同一平台所有请求的 Cookie **必须经过统一入口**（如百度 `BaiduApi.ck()` → `BaiduConstants.normalizeCookie()`），不得逐个手工拼 Cookie。
- 解析响应先判 `response.isSuccessful`，再判业务 `errno`；两者都失败要能区分日志。

### 3.6 错误处理与用户提示

- 面向用户的文案要**可行动**：说清原因与下一步（「登录态无效，稍后自动重试」≠「失败」）。
- 区分「保存失败（本地异常）」与「凭证无效（服务端判定）」，不要一律提示重新登录。
- 底层返回结构化结果（如 `XxxResult(saved, message, reviewUrl)`），UI 层只负责展示，不在 `network` 层拼提示语。

### 3.7 Compose UI

- 状态用 `mutableStateOf` / `collectAsState`；跨重组回调用 `rememberUpdatedState`，协程里始终取最新实现。
- **不要对「会变化的能力」做一次性快照**：例如 `JcefHolder.browserReady` 必须被观察，不能写成 `val app = JcefHolder.app()`。
- 提示统一走 `SnackbarController.show(...)`；对话框、页面覆盖层沿用 `ui/screens` 现有组件，不另起炉灶。

### 3.8 数据存储与安全

- 账号凭证加密落库（`DbKeyStore`），**禁止**明文写盘或写日志；新增字段走既有迁移机制。
- 机器指纹必须跨平台：Windows 注册表 `MachineGuid`、macOS `IOPlatformUUID`、Linux `/etc/machine-id`，任一平台缺失要降级而不是崩溃。
- 桥接 / 本地 HTTP 仅监听 `127.0.0.1`，必须校验来源与随机 token。

### 3.9 构建与脚本

- 版本号**唯一来源**是 `version.txt`（第一行版本、第二行 `Beta=1/0`）。
- **`start.sh` / `start.bat` / `run.ps1` / `run.bat` 内不得写死版本号**，也不因发版改版本；版本统一由 `version.txt` 驱动。
- 平台差异只走 `start.sh` 的平台识别分支；新增启动参数必须同时在 `start.sh` 用法说明里登记。
- 构建脚本改动（`desktop/build.gradle.kts`、`installer/`）需在 PR 说明里写清影响平台。

## 4. 内嵌浏览器与登录态采集规范

1. **初始化**：`JcefHolder.initBlocking()` / `initInBackground()`；`YUNXPC_SKIP_JCEF=1`（或 `--no-jcef`）时跳过并让登录页自动回退粘贴模式。
2. **不可用要有原因**：登录页通过 `JcefHolder.unavailableReason()` 展示具体原因并提供重试，不得只显示「不可用」。
3. **jawt 预载**：JCEF 初始化前按 `java.home` 显式 `System.load` `libjawt`，保证 IDE 直接运行也能起。
4. **Cookie 采集**：`JcefLoginPane` 每 `POLL_INTERVAL_MS`（1.5s）轮询 Cookie，命中平台必需键即**自动保存并回调**，不需要用户点保存。
5. **域名匹配**：一律按**域后缀**匹配（`a.b.com` 匹配 `b.com`），禁止 `contains(".b.com")` 这类子串判断；同名 Cookie 保留域名最具体的一条；过滤空名 / 空值。
6. **自动重试**：保存失败后间隔 `AUTO_RETRY_MS`（10s）自动重试；连续失败才展示原因。
7. **保存前归一化**：落库前调用平台 `normalizeCookie()` 补齐必备 Cookie（如百度 `PANWEB=1`）。

## 5. 新增网盘平台接入清单

新增平台按以下顺序落地，缺一项视为未完成：

1. `data/network/XxxConstants.kt`：登录 URL、UA、`app_id`、Cookie 校验与 `normalizeCookie()`。
2. `data/network/XxxApi.kt`：登录态校验、`bdstoken` 类 token、分享信息、列目录、转存、直链；所有请求走统一 Cookie 入口。
3. `data/repository/XxxAccountRepository.kt`：账号增删改查、Cookie 归一化后加密落库。
4. `data/repository/XxxResolveRepository.kt`：分享链接解析、提取码、目录树、转存。
5. `ui/login/XxxLoginScreen.kt`：`JcefLoginPane` 优先 + 粘贴兜底。
6. 平台枚举 / 路由 / 图标 / 设置项 / 下载页映射同步补齐。
7. 更新 README「支持平台」、`docs/FEATURES.md`、本文件「版本记录」。

## 6. 版本管理与文档同步

- **发版**：只改 `version.txt`；`Releases.md` 写该版本对外更新说明（新增 / 修复 / 优化分组，条目必须具体）。
- **同步义务**：功能变化 → README「功能」与「最近更新」；规范或架构变化 → 本文件；对外细节 → `docs/`。
- **文档不得留占位**：禁止 `待生成`、`TODO`、空条目、`commit: 待生成` 之类的假数据，必须写真实内容与真实哈希。

## 7. 版本记录（changelog）

> 规则：每条版本必须有**非空的 `changes` 列表**与**真实 commit 短哈希**（7 位，来自 `git log`）。
> `changes` 为空、`commit` 写「待生成」或与实际提交不一致的，一律视为不合格，必须改到合格为止。

### v1.3.3 维护修复（2026-11-02）

- commit: `8e0c6ac`
- changes:
  - 内嵌浏览器：JDK 17 模块导出缺失（`sun.awt` / `sun.lwawt`）与 `libjawt` 缺失导致 macOS / Linux 起不来，补 `--add-exports` / `--add-opens` 并按 `java.home` 预载 jawt；登录页展示具体不可用原因 + 重试
  - 登录态采集：`JcefLoginPane` 改为观察 `browserReady` 并在就绪后重建浏览器，1.5s 轮询 Cookie 自动保存，保存失败与凭证无效分开提示，10s 自动重试
  - 百度网盘：Cookie 域名改按域后缀匹配，保存与请求前补齐 `PANWEB=1`，`bdstoken` 拉取失败回退解析网盘首页 HTML，修复 errno=-6
  - 本地浏览器导入：平台域名后缀去掉前导点，避免漏掉 `pan.baidu.com` 等平台自有域
  - 迅雷登录：短信验证码绑定 `creditkey` / `token` 上下文，空 token 不下发，失败原因细化为验证码错误 / 过期 / 换 token 失败，支持应用内验证面板兜底
  - 安全：机器指纹跨平台化（macOS `IOPlatformUUID`、Linux `/etc/machine-id`），修复非 Windows 保存 Cookie 失败
  - 构建：Gradle 发行包走腾讯云镜像；仓库地址统一为 `RichelYu1998/YunX-Desktop`
  - 文档：README 增补登录方式、errno=-6 排查、macOS / Linux 调试说明；新增 skill.md 与 skill.docx

### v1.3.3（2026-10-31）

- commit: `87ff5d6`
- changes:
  - 新增 P2P 下载：磁力 / BT 种子 / ed2k，Tracker 服务器管理页、DHT 开关、peer 概览
  - 修复 P2P 任务「继续」被内置下载器执行；引擎失败原因回读真实错误
  - 新增界面与体验：下载列表常驻滚动条、失败任务置顶、添加弹窗实时重算名称、主窗口启动位置可设置
  - 修复内嵌浏览器原生包解压失败（`tar.xz` 换回 `tar.gz`）

### v1.3.2（2026-10-31）

- commit: `33f9ba5`
- changes:
  - 浏览器插件冷却倒计时与视频桥接，HLS 转 MP4，发布 1.3.2 预览版

### v1.3.1（2026-10-29）

- commit: `2b55bbf`
- changes:
  - 版本号升至 1.3.1，`version.txt` 第二行改为 `Beta=1/0`
  - 支持发布说明里的强制更新字段；下载线程数覆盖通用 HTTP / GitHub / 各视频平台
  - 修复 1.3.0 每次启动误删 WAL 导致登录态与任务被清空

## 8. 提交规范

- 采用 Conventional Commits + 中文描述：`feat(模块): ...`、`fix(模块): ...`、`style(模块): ...`、`docs: ...`、`chore(...)`、`perf(...)`。
- 一个提交只做一件事；提交信息里写清**用户可见的变化**，不要只写「修改代码」。
- 涉及文档的改动与代码改动同批提交时，文档提交单独一条，便于回溯。

## 9. 提交前自检清单

1. `./start.sh build`（或 `.\run.ps1 build`）编译通过，无警告级错误。
2. 无 `println`、无明文凭证、无新增 `待生成` / 空 `changes`。
3. 新增平台 / 新接口已登记到 README 与本文档。
4. 版本号未被启动脚本改动，`version.txt` 与 `Releases.md` 一致。
5. 提交信息符合第 8 节，推送前 `git status` 无多余文件。