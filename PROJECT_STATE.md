# IVAN CINEMA · 项目状态（单一事实来源）

> 最后更新：2026-10-02　本文件随源码进仓库（**不含任何凭据**）。凭据见 `_dsx_probe/SECRETS_AND_STATE.md`（本地，不进仓库）。

---

## 一、这是什么

自用 / 小圈子（约 20 人）的 Android 影视聚合 App。从约 20 个 MacCMS（苹果CMS）采集源聚合内容、去重、播放。
**Android only**，只支持 type=1（MacCMS JSON API），type=3（spider jar）明确不做。

- 包名 `com.ivan.cinema`　minSdk 26 / targetSdk 34
- Kotlin + Jetpack Compose + Media3 + Room + OkHttp + Coil
- 当前版本 **1.0.11（versionCode 12）**
- 仓库 https://github.com/IvanXxxxyyuffff/ivan-cinema （public，为了 raw 直连更新清单）
- 落地页 `docs/index.html`（扫码下载，单文件、二维码客户端生成、无第三方依赖）

---

## 二、构建与发布

### 编译
```
set JAVA_HOME=D:\Android\jdk-17.0.2&& set ANDROID_HOME=D:\Android\Sdk&& D:\Android\gradle-8.7\bin\gradle.bat -p "E:\.zcode\workspace\default\ivan-cinema" assembleRelease --console=plain
```
产物 `app/build/outputs/apk/release/app-release.apk`（约 3.0MB，R8 混淆 + 资源压缩；debug 版约 21MB 仅供调试）。

### 发版流程（无 git，全走 GitHub API）
1. 改 `app/build.gradle.kts` 的 versionCode / versionName
2. `assembleRelease`
3. 建 Release：`POST /repos/{owner}/{repo}/releases`
4. 传 APK：`POST https://uploads.github.com/.../releases/{id}/assets?name=...`（Content-Type: application/vnd.android.package-archive）
5. **更新 `update.json`（versionCode 必须大于 APP 当前值）** —— 这一步最容易漏
6. 同步 `docs/index.html` 里的 APK 地址（**出现两次**：按钮 href 和脚本里的 `APK_URL` 常量）
7. 复制到桌面 `C:\Users\Administrator\Desktop\IVAN-CINEMA.apk`
8. 推源码：`powershell -File _dsx_probe\sync.ps1`

⚠️ **踩过的坑：清单没更新 = 没人收得到更新。** v1.0.10 就是这样废掉的（Release 建了、APK 传了，`update.json` 忘了改）。发版后务必用 `curl` 验证线上 `update.json` 的 versionCode。

### 源码同步脚本 `_dsx_probe/sync.ps1`
走 Git Data API：本地算 git blob sha1 与远端比对，**只推变化的文件**，单次 commit。
候选集 = 远端已有文件 ∪ 本地文件（所以**新增文件也能推上去**）。
排除：`.gradle/ build/ keystore/ .secrets/ .idea/`、`local.properties`、`*.apk/*.jks/*.keystore`。
> keystore 与密码文件靠这个排除列表保护，**不要用 git 手工提交整个目录**。

---

## 三、已完成的重大改造（按时间）

### 早期
- MacCMS 源识别与池化、源健康探测（启动静默核验、24h 缓存、取前 10 可信源）
- 播放器：手势（单击/双击/横拖快进/竖拖亮度音量）、倍速记忆、内嵌字幕开关、换源、断点续播
- 下载（Media3 DownloadService + 前台服务通知）
- UI 三次转向：Aurora → 液态玻璃 → **腾讯视频式（当前）**

### 2026-10-02 这一轮（大量）
1. **应用内自更新**（v1.0.3）：下载 APK → FileProvider → 系统安装器，全程不跳浏览器；镜像依次重试（ghfast/gh-proxy/ghproxy）；安装包用**独立证书校验客户端**（源接口那个是 trust-all，绝不能用于 APK）
2. **观看记录云端同步常驻化**（v1.0.3）：启动拉取 + 播放进度 60s 节流上传 + 退出强制同步
3. **全量 UI/UX 精修**（v1.0.4）：修掉 10 个 P0，详见第六节
4. **观感统一为平面深底**（v1.0.6）：去掉全屏模糊海报背景，卡片改不透明实色 + 1px 描边，只保留底栏悬浮
5. **点播放自动全屏** + **退出后迷你播放条**（v1.0.6）
6. **自动换源**（v1.0.8）：线路失效自动试下一条（最多 3 条），保留进度，界面说明"已自动切到 X"
7. **追剧订阅 + 每日更新提醒**（v1.0.8）：WorkManager 每日轮询 + 本地通知
8. **首页流式加载**（v1.0.9）：见第四节「最重要的性能修复」
9. **导航改版**（v1.0.9）：首页 / 追剧 / 我的；设置、下载、收藏变成推入页
10. **片单收藏**（v1.0.9）
11. **共享错误上报**（v1.0.9）：崩溃自建兜底 + 播放失败静默上报，**不含任何身份信息**
12. **启动更新弹窗**（v1.0.11）
13. **全量细节与动效审计后的修复**（v1.0.11）

---

## 四、关键架构决策与踩过的坑

### 最重要的性能修复：首页曾经在"等最慢的源"
`Aggregator.category` 原来用 `awaitAll()` —— 8 个源全部返回，网格才拿到第一条数据、才发出第一个封面请求。**每个源最多 10 秒超时，只要有一个卡住，整个首页干等 10 秒。**
用户抱怨的"封面十几秒才出来"其实**不是封面慢，是数据没到**。

现在 `Aggregator.categoryStream(...)` 返回 `Flow<List<VodItem>>`：谁先回来先上屏，**只追加不重排**（内容不会在手指底下跳），骨架屏只显示到第一批结果到达。
`sourceWeight: ((String) -> Int)?` 用于排序源（**降权不隐藏**——绝不因为源差就丢弃内容）。

### MacCMS 过滤参数是 `t` 不是 `tid`
`ac=detail&tid=6` 会被忽略并返回整个库（实测 156,636 条）；必须用 `t=6`（4,961 条）。这是本项目最贵的一个 bug。

### 搜索多源 hits 曾被清空
`SearchScreen` 里 `exist.hits.clear(); exist.hits.addAll(m.hits)` —— 因为 `Aggregator` 对同一 key 重发的是**同一个对象**，`exist === m`，这行等于清空自己。**所有多源片子的 hits 全归零**，连带详情页"N 源"不显示、换源按钮不出现。现在直接 `map[m.key] = m`。

### 封面加载
- Coil 的 client 原本**不带 Referer/UA**，国内图床普遍需要 Referer，会直接 403（而 API client 是带的，所以 API 正常、图挂）
- 超时过宽（连接 6s + 读取 10s、无总超时）→ 改为连接 4s / 读取 6s / 总 8s，让失败快速暴露
- `allowHardware(false)` 是**故意的**（截图测试与模糊需要软件位图），别改回去
- 预取：`prefetch(ctx, urls)`，上限 12 张

### 推入页必须自带不透明背景
设置页/下载页原来是根 tab（坐在全局背景上），改成推入页后**下层内容直接透上来**，出现"两个页面叠在一起"。用 `Modifier.opaqueScreenBackground()`。
> 这是"改导航结构"时最容易漏的一类回归。新增推入页时务必加。

### 更新检查
- **原来没有推送**，只有设置页角标 + 仅启动时检查一次 → 现在启动弹窗
- `fetchFastestMirror` 曾是 `repeat(n){ ch.receive() }`，**要收完所有镜像才返回**，耗时由最慢的决定 —— 与"谁快用谁"相反。现在拿到第一个成功结果就返回并取消其余
- "以后再说"按 versionCode 记录，同一版本只打扰一次（`UpdatePromptPrefs`）

### Supabase
- **合成邮箱**：Supabase Auth 协议层必须有 email，所以用户名映射成 `用户名@ivan-cinema.app`；用户名写进 `user_metadata`，身份位显示用户名而非合成邮箱
- **必须关闭「Confirm email」**：合成邮箱收不到任何邮件，开着它注册必然失败（会去发确认信 → 撞频率限制或被校验挡掉）。已在用户后台关闭并验证通过（HTTP 200 + 自动确认）
- 老账号兼容：输入里带 `@` 时按真实邮箱原样登录
- 共享源健康与错误上报用**原子自增 RPC**（PostgREST 的 upsert 只能整行替换，做不了 `ok = ok + 1`）
- `error_report` 表**刻意没有任何身份列**（不记 user_id / 用户名 / IP），只存错误类型 + 源 + App 版本 + Android 版本 + 机型，保留 30 天
- 隐私决定写在 SQL 注释里，改之前先读

### 动效系统
- `MotionKind`：General 246.74/1.0、Sheet 438.65/0.8、Snappy 631.65/1.0（Apple response 换算，保留）
- `press` 令牌：`press.card = 0.97f`、`press.control = 0.94f` —— **不要再写 0.92/0.95/0.96/0.98 这类字面值**
- `pressDip` 必须与 `clickable` **共用同一个 `MutableInteractionSource`**，否则按压反馈完全不触发（首页搜索框和「更多」曾因此完全没有反馈）
- `MotionPrefs.reduce` 已持久化（此前只在内存里，重启即失效）
- 位移/缩放用 spring，淡入淡出用 `motionFade`/`motionExit`；**不要在组合期读动画状态**（会导致整棵树每帧重组）
- 尊重减少动效的：StaggerIn、PlayingBars、ScannerLine、海报呼吸动画、翻页/搜索动画

### Room 迁移
当前 version 6。历史：1→2 搜索历史表、2→3 `watch_history.vodId`、3→4 `userId`、4→5 `followed` 表、5→6 `favorites` 表。
`WatchEntry` / `FollowEntry` / `FavEntry` 都有 `userId`；本机表此前是设备级的，换账号会串记录。

### 已知限制
- `watch_history` 本机主键仍是 `vodKey` 单列（不是 `(userId, vodKey)`），同设备多账号看同一部片会互相覆盖。云端有 RLS 隔离，本机是"尽力而为"
- 观看记录的云端同步**不含 vodId**（Supabase 表没这列，加列需要重跑 SQL），跨设备续播靠"按片名回查"兜底
- 追剧/收藏**没有云端同步**（只有 WatchEntry 接了）
- 应用内更新**没有 APK sha256 校验**（只依赖 HTTPS 证书）

---

## 五、设计系统速查

| 令牌 | 值 |
|---|---|
| canvas / surface / surfaceRaised | `#0C0B10` / `#17171C` / `#24242B` |
| ink / inkMuted | `#F5F3EF` / `#A9A5B4` |
| accent（唯一强调色） | `#D9BC82`，只给「激活导航 / 播放指示 / 进度 / 主操作」 |
| accentInk | `#241C08` |
| badge / badgeInk | `#A63412` / 白 |
| brand | `#E8B23A`（字标与 SVIP 徽章） |
| danger / dangerOnGlass | `#D96A6A` / `#FFB4B4` |
| 玻璃 → 实色 | `Modifier.liquidGlass` 现在是**不透明 `pal.surface` + 1px 描边**，不是半透明玻璃。`LiquidParams` 里只有 `dropShadow` 和 `topLine` 还有效 |

**统一约定**：次级按钮 = 透明填充 + `pal.hairline` 1px 描边 + 选中时 `pal.accent`（`SelectPill` 已抽出）；返回胶囊 = 48dp + 黑 0.55 + 白 0.22 描边 + 22dp 白箭头；页面标题 = `headlineSmall`；区块标题 = `titleMedium`；触摸目标一律 ≥48dp。

---

## 六、截图取证（重要：本机模拟器不可用）

**本机模拟器的 `screencap` 只工作第一帧，之后画面永久冻结。** 试过 8 种组合全部失败：
默认 GPU / swiftshader_indirect（最小化窗口）/ swiftshader_indirect（可见窗口）/ host / off(guest)，以上 2GB 内存；host + 4GB + 6 核；no-window + swiftshader_indirect + 4GB + 6 核；以及把分辨率降到 540×1200。
`dumpsys SurfaceFlinger --latency` 全 0 —— 根本没有新帧在合成。**降分辨率无效说明不是填充率问题。**

**唯一可行的取证方式：离屏渲染。**
- `app/src/androidTest/java/com/ivan/cinema/ScreenshotTest.kt` —— MainActivity 内所有页面，走 `createAndroidComposeRule<MainActivity>()` + `onRoot().captureToImage()`
- `app/src/androidTest/java/com/ivan/cinema/PlayerScreenshotTest.kt` —— 播放页是**独立 Activity**，要用 `createEmptyComposeRule()` + `ActivityScenario` 手动启动；用 `view.draw(Canvas)` 会全黑（硬件图层画不出来）
- 产物写进 MediaStore 的 `Pictures/ivanshots/`（应用私有目录 Android 11+ 对 adb shell 不可读，且测试跑完 Gradle 会卸载应用）
- 取回：`adb pull /sdcard/Pictures/ivanshots/`
- **导航要点**：`back()` 在栈为空时会直接退出 APP，所以用 `scenario.recreate()` 回根页；先滚再点会点不到，顺序要安排好

**另一类坑：Gradle 缓存会损坏。** 报 `Could not read workspace metadata from .../metadata.bin` 时，删 `C:\Users\Administrator\.gradle\caches\transforms-4`、`kotlin-dsl`、`scripts` 与项目 `.gradle`/`.kotlin`，并**杀掉残留的 java 进程**（daemon 会在内存里持有旧索引），再重建。首次重建约 3-4 分钟。

---

## 七、待办 / 未完成

- [ ] **真机验收** —— 未执行。建议重点：启动更新弹窗、设置页是否还叠页、搜索框两边是否一致、下载页返回、首页是否明显变快、自动换源
- [ ] **逐屏实拍复检** —— 未执行（模拟器截图链路不可用，见第六节）
- [ ] **共享源健康与错误上报的 SQL** —— 用户已在 Supabase 执行完毕
- [ ] 应用内更新加 APK sha256 校验 —— 未执行
- [ ] 追剧 / 收藏的云端同步 —— 未执行
- [ ] `watch_history` 本机主键改 `(userId, vodKey)` —— 未执行
- [ ] 投屏、弹幕 —— 明确不做（弹幕：MacCMS 没有弹幕源，做了是假的）
- [ ] **提醒用户轮换 GitHub token**（已出现在对话记录里）

---

## 八、源码结构速查

```
app/src/main/java/com/ivan/cinema/
├── IVANApp.kt                 启动：缓存/源健康/账号/更新检查/错误上报兜底/追剧调度
├── MainActivity.kt            导航栈 + 推入转场 + 搜索覆盖层 + 底栏 + 迷你播放条 + 更新弹窗
├── data/
│   ├── MacCmsApi.kt           接口客户端（注意：过滤参数是 t；trust-all 只给源接口）
│   ├── Aggregator.kt          categoryStream（流式、只追加）/ searchAll / expandHits / mergeKey
│   ├── SourcePool.kt          源列表（assets/sources.json，可被外部文件覆盖）
│   ├── SourceHealth.kt        启动探测 + 24h 缓存 + 折入共享健康降权
│   ├── SharedHealth.kt        共享源健康（上报/读取/rankLines，降权不隐藏）
│   ├── ErrorReporter.kt       崩溃兜底 + 非致命错误上报（无身份信息，保留 30 天）
│   ├── Account.kt             账号 + Supabase 会话 + 观看记录同步 + 合成邮箱
│   ├── SupabaseClient.kt      GoTrue/PostgREST 裸客户端（全部返回 Result，不抛）
│   ├── SupabaseConfig.kt      URL / publishable key
│   ├── UpdateChecker.kt       5 镜像竞速取更新清单（第一个成功即返回）
│   ├── ApkUpdater.kt          应用内下载 + FileProvider 安装（独立证书校验客户端）
│   ├── FollowStore.kt         追剧订阅门面
│   ├── FollowWorker.kt        WorkManager 每日检查更新集数 + 本地通知
│   ├── FavStore.kt            片单收藏门面
│   └── NowPlaying.kt          「正在播放」进程级状态（迷你播放条用）
├── db/AppDb.kt                Room v6：watch_history / search_history / followed / favorites
├── player/
│   ├── PlayerActivity.kt      播放器（手势/换源/自动换源/上报/进度保存）
│   ├── PlayResolver.kt        分享页解析
│   ├── DownloadCenter.kt      下载管理
│   └── IVANDownloadService.kt 下载前台服务
└── ui/
    ├── Backdrop.kt            平面深底背景 + opaqueScreenBackground()
    ├── HomeScreen.kt          首页（流式网格 + 预取 + 骨架）
    ├── CategoryScreen.kt / FilterScreen.kt / SearchScreen.kt
    ├── DetailScreen.kt        详情（hero/播放/线路/选集/简介/追剧/收藏）
    ├── MyScreen.kt            我的（身份位/观看历史/片单/下载/设置）+ 追剧 tab
    ├── FavoritesScreen.kt     片单收藏
    ├── DownloadScreen.kt      下载（按剧集分组、按状态可点）
    ├── SettingsScreen.kt      设置（检查更新/减少动效/封面缓存/搜索历史）
    ├── LoginScreen.kt         登录注册
    ├── UpdatePrompt.kt        启动更新弹窗
    ├── MiniPlayer.kt          迷你播放条
    ├── components.kt          PosterCard / FilmTile / 骨架 / EmptyState(简版)
    ├── components/LiquidGlass.kt   liquidGlass（实色）/ LiquidCard / LiquidBar / LiquidNavBar / SelectPill
    ├── components/GlassSurfaces.kt EmptyState(完整版) / PlayingBars / ScannerLine / ThinProgress
    ├── components/Motion.kt   StaggerIn / pressDip / press 令牌 / portal 系列
    └── theme/                 Theme（palette/type/space/radius/springs）/ MotionPrefs
```

---

## 2026-10-02 第六轮：截图取证体系 + 五项功能 + ICON

### 截图取证体系（细节见 `_dsx_probe/HARNESS.md`）
本地 mock MacCMS 源 + `am instrument` 直跑，全量 16 屏一轮约 70s
（原先用 `gradle connectedDebugAndroidTest` 约 4 分钟，且经常拍到错误状态）。
脚本：`fastshots.ps1`（主页面 16 屏）/ `playershots.ps1`（播放页）/ `contactsheet.ps1`（总览图）。

### 功能改动
- **播放页换源**：右上角文字胶囊「源名 ⇄」改成一枚图标；点开下拉列出全部线路，
  按 `SharedHealth.rankLines` 的可信度排序，最好的标「推荐」，当前那条标「当前」。
- **播放页清晰度**：档位来自 ExoPlayer **实际视频轨高度**（`onTracksChanged` 收集），
  不是硬编码列表 —— 采集源的 `vod_play_from` 只有 CDN 名，一个清晰度标记都没有（5 个真实源全探过）。
  单码率时只有「自动」，这是诚实结果。应用方式：`setMaxVideoSize(MAX, height)`。
- **动漫子专栏**：分类页在 `tab == ANIME` 时多一排 chip（全部/国漫/日漫/欧美/港台）。
  `AnimeSub` 用 matchers 命中各源不统一的命名（国产动漫 / 日本动漫 / 日韩动漫 / 港台动漫）；
  子专栏 `defaultTid = null` **不兜底** —— 兜底会把整库动漫当成「国漫」显示。
- **国漫/日漫按热度排**：两段合成 —— ① B 站国创/番剧榜位次（权威，但只覆盖 5~15% 的条目）
  ② 源站 `vod_hits` 播放量（未上榜条目的依据）。
  腾讯 / 爱奇艺 / 优酷的公开榜单接口都试过：腾讯 `ret=0` 但 module 为空、爱奇艺 `A00000` 但 data 只有 `{base:{}}`、
  优酷 webrank 页压根没有动漫榜模块 —— 都要登录态，所以只用 B 站。
  ⚠️ **必须在榜单落定前压住网格不上屏**（`heatSettled`）：上屏后再重排会被 LazyGrid 的 item key
  锚定住滚动位置，视觉上"顺序根本没变" —— 这是本轮踩到的真实缺陷，不是截图工具的锅。
- **搜索框动效**：`portalReveal()` 从矩形裁剪改成**胶囊路径**裁剪 —— 左缘从右端往左推，
  左缘 = 宽-高 时正好退化成一个圆，所以整段读成「缩成圆 → 再展开成搜索框」，
  中间不换元素、不会两个形状打架。取消按钮延迟淡入（它不在裁剪范围内，不延迟会"字先到、框后到"）。
- **APP ICON 重做**：金属渐变金环 + 环内径向透光 + 圆角播放三角 + 深色径向底；
  几何全收在 r=36 安全区内；补了 `monochrome` 层（Android 13+ 主题图标）。

### 新增数据模型字段
`VodItem.hits`（源站 `vod_hits` 播放量）。部分源不返回该字段，此时为 0。
