# IVAN CINEMA · 项目状态

> 最后更新：2026-10-02 01:40
> 本文件是项目的"唯一事实来源"，上下文丢失后从这里恢复。

## 一、项目是什么

个人自用的安卓影视聚合 APP（**非商业、不分发**）。
- 技术栈：Kotlin + Jetpack Compose + Media3(EXOPlayer) + Room + OkHttp（无第三方网络库）
- 包名：`com.ivan.cinema`　minSdk 26 / targetSdk 34
- 项目路径：`E:\.zcode\workspace\default\ivan-cinema`
- 数据来源：20 个苹果CMS(MacCMS)采集源，纯客户端聚合，无自建后端

## 二、构建环境（本机已配好）

| 组件 | 位置/版本 |
|---|---|
| JDK | `D:\Android\jdk-17.0.2`（JAVA_HOME 已持久化） |
| Android SDK | `D:\Android\Sdk`（ANDROID_HOME 已持久化，platforms;android-34 + build-tools 34.0.0） |
| Gradle | `D:\Android\gradle-8.7`（无 wrapper，直接用绝对路径调用） |
| AGP / Kotlin | 8.3.2 / 1.9.23，Compose BOM 2024.05.00 |

**编译命令（release，性能最优）**：
```
set JAVA_HOME=D:\Android\jdk-17.0.2&& set ANDROID_HOME=D:\Android\Sdk&& D:\Android\gradle-8.7\bin\gradle.bat -p "E:\.zcode\workspace\default\ivan-cinema" assembleRelease --console=plain
```
产物：`app\build\outputs\apk\release\app-release.apk`（**2.77MB**，R8 混淆 + 资源压缩；debug 版 20.2MB 仅供调试）

**签名**：`keystore\ivan-cinema.jks`（alias `ivan`，RSA4096，30 年；密码在 `keystore\password.txt`）——**此目录已排除出 git，绝不可丢**

## 三、已完成功能

**首页（腾讯视频式布局）**：搜索框（内含筛选入口）→ 分类 Tab（8 个：电影/剧集/综艺/动漫/纪录片/少儿/体育/短剧）→ 大 Banner（16:9 + 片名 + 一句话 + 指示点）→ 继续观看横滚 → "热播推荐"标题 + **两列大卡**（竖版封面 + 左上角标 + 底部更新信息 + 片名 + 一句话副标题）

**搜索**：输入即搜（320ms 防抖）、历史/热门两列两侧进场、结果按命中源数排序、Portal 专属转场（搜索框裁剪揭示 + 首页内容两侧退散 + 不自动弹键盘）

**筛选页**：分类/地区/类型/年份/排序 五个维度（透传 MacCMS `t/area/class/year/by`），选中即刷新

**详情页**：海报大图 → **金色主播放按钮**（有记录时变"继续观看 第N集"）→ 线路切换 → 选集（长按下载）→ 折叠简介（优先中文）；**自动补全多源**（单源命中时按片名搜全网，让播放器有线路可换）

**播放页（腾讯视频式）**：无玻璃面板，黑渐变遮罩 + 细进度条 + 中央播放键 + 右下功能行（下一集/倍速/字幕/选集/全屏）；手势（单击显隐 / 双击暂停 / 横滑快进 / 左竖滑亮度 / 右竖滑音量）；记忆倍速；**一键换源**（保持进度）；播放失败给"换线路 + 重试"

**下载**：长按集数入队、全部暂停/继续、离线播放（Media3 DownloadService + SimpleCache）

**账号**：本地账号（加盐 SHA-256）+ **Supabase 模式**（邮箱注册登录 + 观看记录双向同步，配置见下）

**更新推送**：启动静默检查 `update.json` → 有新版本时设置页红标 + "去更新"；**5 个镜像并发竞速**（GitHub 官方 / ghfast.top / gh-proxy.com / jsDelivr / ghproxy.net），**检测到 VPN 时官方直连优先**

**全局**：沉浸式（edge-to-edge，背景铺到状态栏）、导航栈 + 横向推入转场（返回逐级回退，栈底再返回退出 APP）、全部可点元素有按压动效、高刷请求（120Hz）

## 四、关键架构决策（踩过的坑，勿重犯）

1. **MacCMS 分类筛选参数是 `t` 不是 `tid`** —— 用错参数源会忽略筛选并返回全库（曾导致首页四个分类内容完全重复）
2. **页面必须有不透明背景** —— 否则下层页面透出来（筛选页曾因此与搜索页"重叠"）
3. **搜索覆盖层必须画在推入栈之前** —— 否则从搜索页进筛选页时两层同时可见
4. **Column 内的列表用 `weight(1f)` 而非 `fillMaxSize()`** —— 否则会与上方内容重叠
5. **`Animatable.value` 不是 snapshot state** —— 要镜像进 `mutableStateOf`，绘制期 lambda 才能跟着刷新
6. **播放器只拉单源详情时线路索引恒为 0** —— 详情页传的 selectedLine 是聚合列表下标，会越界导致永久"解析中"
7. **Compose 里不能用 `FQ 名` 调扩展函数**（如 `androidx.compose.foundation.border(...)`）—— 必须 import 后调用
8. 源站片源可能"挂羊头卖狗肉"（赌博推广片头）—— 换源是唯一出路，所以多源补全必须生效

## 五、数据与外部资产

| 资产 | 地址 |
|---|---|
| GitHub 仓库 | https://github.com/IvanXxxxyyuffff/ivan-cinema （**public**，为了 raw 直连更新清单） |
| 更新清单 | https://raw.githubusercontent.com/IvanXxxxyyuffff/ivan-cinema/main/update.json |
| Release 下载 | https://github.com/IvanXxxxyyuffff/ivan-cinema/releases |
| Supabase 项目 | https://ooyxaaabfehhknwljbnh.supabase.co |
| Supabase 建表 | `docs/supabase.sql`（已由用户在 SQL Editor 执行完毕，表 `watch_history` + RLS 已生效） |

**凭据（token / key / 密码）不写在本文件**，见 `E:\.zcode\workspace\default\_dsx_probe\SECRETS_AND_STATE.md`

## 六、待办 / 未完成

- [x] ~~v1.0.2 发布~~ —— 已完成（Release ID 401209013）
- [x] ~~**v1.0.3：应用内更新**~~ —— 已完成（Release ID 401216119，APK 2,922,758 B，versionCode 4）
      点「更新」在 APP 内下载（ghfast/gh-proxy/ghproxy 依次重试）→ 进度条 → 拉起系统安装器，全程不跳浏览器
- [x] ~~**v1.0.3：观看记录云端同步常驻化**~~ —— 已完成
      启动自动拉取 / 播放进度 60s 节流上传 / 退出播放页强制同步
- [ ] **提醒用户轮换 GitHub token**（已出现在对话记录中）
- [ ] 真机验收 —— **未执行**。重点：应用内更新链路（含「安装未知来源」授权）、A/B 设备观看记录同步
- [ ] 应用内更新未做 APK 完整性校验（只依赖 HTTPS 证书），后续可加 sha256 比对 —— **未执行**

## 六之二、更新与同步的实现位置（改之前先读）

| 关注点 | 文件 |
|---|---|
| 更新清单拉取（5 镜像并发竞速） | `data/UpdateChecker.kt` |
| APK 下载 + 进度 + 安装 | `data/ApkUpdater.kt` |
| 安装包共享路径 | `res/xml/file_paths.xml`（只暴露 `filesDir/apk/`） |
| 权限 / FileProvider 声明 | `AndroidManifest.xml` |
| 更新 UI（按钮随状态机变化） | `ui/SettingsScreen.kt` |
| 云端账号 + 观看记录同步 | `data/Account.kt`、`data/SupabaseClient.kt` |
| 同步触发点 | `IVANApp.onCreate`（启动）、`PlayerActivity.saveProgress` / `onDispose`（进度） |

## 七、源码结构速查

```
app/src/main/java/com/ivan/cinema/
├── IVANApp.kt                 启动：缓存/源健康/账号/更新检查
├── MainActivity.kt            导航栈 + 推入转场 + 搜索覆盖层 + 底栏
├── data/
│   ├── MacCmsApi.kt           采集源 API（t/by/area/class/year/lang 参数、GBK 容错）
│   ├── Aggregator.kt          多源并发聚合 + 归并去重 + expandHits(补全多源)
│   ├── SourceHealth.kt        启动静默探测源（可信度前 10，24h 缓存）
│   ├── ClassCache.kt          分类映射缓存（assets 预置 + 热更新）
│   ├── Account.kt             本地账号 + Supabase 模式
│   ├── SupabaseConfig.kt      ← 用户填 URL / publishable key
│   ├── SupabaseClient.kt      Auth + watch_history REST
│   └── UpdateChecker.kt       5 镜像竞速 + VPN 检测
├── db/AppDb.kt                Room：watch_history(本地) + search_history
├── player/PlayerActivity.kt   腾讯视频式播放器 + 手势 + 换源 + 下载
└── ui/                        各屏 + components(LiquidGlass/Motion) + theme
```
