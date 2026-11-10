# TiebaTS — modern Xposed API 102 fork

提供修改百度贴吧底栏等个性化功能的 Xposed 模块。An Xposed module for Baidu Tieba.

> 本仓库是 [GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS)（原作者 **GM**）的 fork，由 **AIKA1024** 维护。
> 上游已于 **2024-08** 停止更新，最后适配的客户端是贴吧 **12.66.1.0**；
> 本分支继续维护，迁移到现代 Xposed API 并适配贴吧 22.x。两者互不隶属，本分支的问题请发到
> [本仓库 Issues](https://github.com/AIKA1024/TiebaTS/issues)。

[![Android CI](https://github.com/AIKA1024/TiebaTS/actions/workflows/android.yml/badge.svg)](https://github.com/AIKA1024/TiebaTS/actions)

## 与上游的区别

| | 上游 3.0.3-beta (2024-08) | 本分支 |
|---|---|---|
| Xposed API | 旧 `de.robv.android.xposed` API 82 | 现代 `io.github.libxposed.api` **API 102** |
| 入口声明 | `assets/xposed_init` + Manifest meta-data | `META-INF/xposed/{module.prop, java_init.list, scope.list, native_init.list}` |
| Hook 模型 | `XC_MethodHook` before/after | 拦截器链 `hook(Executable).intercept(...)`，由兼容垫片还原旧语义 |
| 反射工具 | 框架提供的 `XposedHelpers` | 模块内自带实现（API 102 禁止访问旧包） |
| 模块资源 | `XModuleResources` 隐藏 API | 直接从模块 APK 读取 assets |
| 版本兼容 | 按规则的 `requiredVersion` 门槛 + 多处 12.x 分支 | 单一下限 22.13.1.1，无按规则版本过滤 |
| 目标客户端 | 12.53.1.0 – 12.66.1.0 | **22.13.1.1 起**（校验基准 22.13.1.1，APK 构建于 2026-10-27），不支持更早版本 |

框架适配集中在 `XposedContext.kt` 和 `compat/` 目录，`hooker/` 下 31 个功能模块基本保持原样。

## 支持范围与要求

- **贴吧客户端**：仅 **22.13.1.1 及以上**。旧版 22.0.x / 12.x 不再支持，源码里的 12.x 版本门槛、老类名候选与按规则的版本过滤均已移除。
- **框架**：LSPosed 2.1+ 或任何实现 libxposed API 102 的框架。
- **不兼容**：LSPosed 1.x、EdXposed、原版 Xposed。声明 `targetApiVersion=102` 的模块在 classloader 层面被禁止访问旧 `de.robv.android.xposed` 类，因此无法双兼容。
- **作用域**：`com.baidu.tieba`（`staticScope=true`，不能添加其它应用）。
- **Android**：minSdk 28。

## 贴吧 22.13.1.1 符号校验结果

用目标 APK（84 MB / 13 个 dex / 498,135 条字符串 / 110,110 个类描述符）逐个比对了源码中写死的符号：

| 类别 | 检查数 | 命中 | 缺失 |
|---|---:|---:|---:|
| 硬编码类名 | 69 | 66 | 3 |
| 字段/方法名（含字符串常量） | 112 | 112 | 0 |
| 方法名 | 29 | 29 | 0 |
| 资源名 | 13 | 13 | 0 |
| DexKit smali 规则 | 7 | 7 | 0 |

### 22.13.1.1 上已消失的旧符号

- `tbclient.ForumRecommend.DataRes$Builder` → 旧类名候选已删除；只在当前版本仍存在的
  `NewRecommend` / `LikeForum` Builder 中按 `like_forum` 字段定位。
- `com.baidu.tieba.enterForum.recforum.message.RecommendForumRespondedMessage` → 在 dex 类表中完全不存在，相关探测代码已删除。
- `com.baidu.tieba.pb.pb.sub.SubPbLayout` → 旧类名候选已删除；保留 `pb.sub.view.SubPbLayout` 与
  `NewSubPbLayoutBinding`，按 `createView`/`b` 定位 holder，找不到时优雅降级。

设置页代理 Activity 固定为 `setting.more.MoreActivity`（`SecretSettingActivity` 已删除，已用 dex 类表核对）；
对应的构造函数式 DexKit 规则与老代理页 hook 一并移除，只保留 `MoreActivity.onCreate` 一条渲染路径。

### 22.x 运行时适配

符号还在不代表行为一致，另外做了几处针对新版的容错：

- **规则缺失不再让全部功能停摆**：上游只要有 1 条 DexKit 规则找不到就直接 `return`，31 个 Hooker 一个都不加载。
  现在改为记录日志并继续逐个安装，仅当过半规则缺失时才弹「规则异常」提示。
- **闪屏相关 hook 改为可选**：方法被移除时只跳过该 hook，不再中断整个 `Purge`。
- **`Switch` 去掉方法索引硬编码**：上游用 `mMethods[3]/[6]/[8]/[11]` 兜底，任何一次重编译都会调错方法；
  现在按 ON/OFF 枚举字段读状态，并遍历无参 void 方法寻找状态切换入口。
- **`NavigationBar` 支持嵌套查找**：新版部分 Activity 把导航栏放进 holder 对象，不再直接挂在 Activity 字段上。
- **native 痕迹探针**改为检测 `io/github/libxposed/api/XposedInterface`，不再检测早已不存在的旧 `XposedBridge`。

### 实机验证（2026-11-06 起）

在 MuMu 虚拟机上完成了一次完整联调：Android 15 / x86_64、KernelSU 3.2.5 + ZygiskNext 1.5.2、
**LSPosed 2.2.1 (7912) / Xposed API 102**、贴吧 **22.13.1.1**。已确认：

- 模块在 `com.baidu.tieba` 及其 remote / bdservice / swan / media 等进程正常加载，
  日志显示 `Framework LSPosed 2.2.1 (7912), Xposed API 102` 与 `TB client detected, version 22.13.1.1`，无任何异常堆栈。
- **DexKit 反混淆在 x86_64 上跑通**（`Deobfuscation complete`）。为此把 `abiFilters` 扩到 `x86_64`，
  并移除了 `packaging.jniLibs` 对 `lib/x86_64/**` 的排除；此前 DexKit 的 x86_64 库被构建配置过滤掉了。
- 24 条规则中有 **6 条在 22.13.1.1 上失效**（`MetaData->getBazhuGradeData`、`AlaLiveTipView.<init>`、
  `dimen.tbds400`、`purge_enter_on_success`、`PersonOftenFuncItemView.<init>`、
  `AdAppStateManager.instance`），模块按设计降级继续运行而不是整体停摆。
  *清理老版本兼容后规则数变为 23 条、失效 5 条；再移除两条 12.x 编辑器规则后为 21 条、失效 5 条（见下节）。*
- **设置入口注入成功**：贴吧「设置」页出现「贴吧TS设置」；接受使用协议后可打开完整设置页，
  开关显示与点击写入均正常（`TS_preferences.xml` 正确落盘）。设置页现由
  `MoreActivity.onCreate` 单一路径渲染。
- 通过直接写入偏好验证 hook 注册链路：`Enabled hooks` 从默认的 `ts_pref, fragment_tab`
  正确扩展到包含 `purge_enter / purge_my / purge_video / auto_refresh` 等条目。
- **设置页署名（4.0.0-beta.16）**：安装签名包后，「关于」分组新增的本分支条目、
  「本分支」与「适配信息」弹窗、外链跳转均在 22.13.1.1 上实机渲染正常，无截断与异常堆栈。
- **移除老版本兼容后（4.0.0-beta.17，2026-11-07）**：实机日志显示
  `Loading TiebaTS, version 4000017`、`Framework LSPosed 2.2.1 (7912), Xposed API 102`、
  `TB client detected, version 22.13.1.1` 与 `5 of 23 deobfuscation rule(s) unavailable`，
  无异常堆栈；设置入口、「关于」分组与「适配信息」弹窗（22.13.1.1 及以上 / 23 条中 5 条失效）
  全部正常渲染。
- **设置页二次进入修复（4.0.0-beta.18，2026-11-07）**：修复「第一次能进设置页，返回后再进不去，
  多点几次连入口按钮都消失」的回归。根因是 `createButton` 每生成一个按钮就注册一次
  `MoreActivity.onChangeSkinType` hook，设置页一次要建 30+ 个按钮，第二次进入即撞上 LSPosed
  「同一 callback class 最多 64 次注册」的限制，抛出
  `IllegalStateException: Reject hook registration ... already registered 64 times`，
  导致 `createButton` 失败、代理页不再渲染、入口按钮也注入不了。
  现改为**每进程只注册一次**换肤 hook，用弱引用绑定列表刷新所有存活按钮；入口按钮加 tag 去重、
  带 `proxyPage` 的实例不再重复注入，容器查找改为容错遍历且整体包在 try-catch 中。
  实机连续 5 轮「进入 → 返回 → 再进入」以及快速连点后重新进入，设置页与入口均正常，
  日志中 `Reject hook registration` 计数为 0。
- **去掉帖子页右侧悬浮红包广告（4.0.0-beta.19，2026-11-10）**：新增 `PurgeFloatAd`
  Hooker，默认启用且设置页有独立开关「隐藏右侧悬浮广告」。实机在 PbActivity 上确认
  `com.baidu.tbadk.widget.RightFloatLayerView` 从 `V.E ... 0,0-264,343`（可见）变为
  `G.E ... 0,0-0,0`（GONE），「广告」文本节点从 UI 层级消失，截图无红包；开关可正常
  切换并落盘，两轮设置页往返无回归。
- **删除彩蛋与冗余的悬浮广告 hook（4.0.0-beta.20，2026-11-10）**：`Purge.kt` 中把
  `RightFloatLayerView` 的所有无参 boolean 方法强制返回 `false` 的旧 hook 与 `PurgeFloatAd`
  功能重复且写法粗暴，已删除，红包浮层统一由默认启用的 `PurgeFloatAd` 负责。同时移除
  「连点原作者 10 次」彩蛋（`ze` 配置、俏皮分组标题、随机 Toast 与解锁逻辑）：
  「真正的净化界面」开关、「隐藏模块」/「隐藏模块（原生）」开关改为设置页常驻显示，
  分组标题与按钮文案统一恢复常规；「原作者」按钮改为打开上游仓库。
- **去掉帖子内信息流广告（4.0.0-beta.20，2026-11-10，真机 OnePlus 7 Pro / LSPosed 2.2.0 API 102）**：
  新增 `PurgeFeedAd` Hooker，默认启用且设置页有独立开关「隐藏帖子内广告」。目标是
  `com.baidu.tieba.funad.view.FeedAdxNativePbView`（主楼下方）、
  `com.baidu.tieba.feed.ad.compact.DelegateFunAdView` 与
  `com.baidu.tieba.funad.view.FeedAdxNativeCommentView`（评论区），
  构造后置 `GONE` 并全局拦截 `View.setVisibility`；不新增 DexKit 规则。
  同一帖子前后对比：`FeedAdxNativePbView` 从 `VFE ... 0,1497-1440,2604`（高 1107）变为
  `G ... 0,0-0,0`，外层 `DelegateFunAdView` 高度收敛为 0，「广告 / 梦幻西游 / 立即下载」
  文本节点全部消失，评论自然顶上、无空白占位，无 FATAL 与 `Reject hook`。

> 以上仍是**加载链路与设置页**的验证，不代表 31 项功能逐项在新版贴吧上生效（例如净化效果、
> 过滤规则的实际表现）仍需在日常使用中观察。

## 已移除的老版本兼容（2026-11-07）

本分支只支持贴吧 **22.13.1.1 及以上**，已移除上游遗留的旧版本代码：

- `Matcher.requiredVersion` 与 `XposedInit` 中按规则的版本过滤（该字段本就没有任何规则赋值，属死代码）。
- `DeobfuscationHelper.isTbSatisfyVersionRequirement` / `isTbBetweenVersionRequirement`，
  连同 `12.55 / 12.56 / 12.57 / 12.58.2.1 / 12.59` 等门槛，统一改为单一下限判断 `isSupportedTbVersion()`。
- `Purge` 里按 12.59 分叉的 `index_tab_info` 处理、设置页与 `TransitionAnimation` 的 12.58.2.1
  贴吧版本判断（Android 14 的 `SDK_INT` 判断保留）。
- 已确认在 22.13.1.1 不存在的旧类名：`SecretSettingActivity`、`RecommendForumRespondedMessage`、
  `ForumRecommend.DataRes$Builder`、`pb.pb.sub.SubPbLayout`，以及老代理 Activity 的构造函数式 hook。
- `MIN_VERSION` 从 `22.0.0.0` 收紧到 `22.13.1.1`；规则数从 24 降到 21（失效 6 → 5）。
- `Purge` 中两条 12.x 编辑器规则（`SpriteMemePan.<init>` 返回 null、「准备展示精灵动画提示控件」
  返回 false）会破坏 22.13.1.1 的 `PbNewInputContainerV2` 初始化，已一并移除。

**保留**的是 22.13.1.1 实测需要的容错：反混淆规则缺失降级、方法/字段签名候选
（`Switch`、`AutoRefresh`、`HistoryCache`、`RedTip`、`FrsTab` 的 ViewPager 类型）、闪屏 hook 的可选包装。
`12_57_frs_pre_request` 一类字符串是 AB 测试的 sid 值而非版本号，保留原样。

## 已知限制

- **楼层点按效果（Ripple）**：新版贴吧重构了楼中楼布局，即使探测到候选类也可能无法生效，需实机调整。
- **关注吧缓存**：依赖 protobuf 字段名 `like_forum`；22.13.1.1 上未找到对应 Builder，日志会提示跳过该功能。
- **5 条 DexKit 规则失效**（见上）：影响对应反混淆定位的功能，模块会降级运行。
- **「真正的净化界面」开关（`purge`）曾导致帖子页崩溃，现已修复**：原崩溃点为
  `PbNewInputContainerV2.l0 -> RelativeLayout.getParent()` 空指针，元凶是 `Purge` 里两条
  12.x 编辑器规则（`SpriteMemePan.<init>` 返回 null、「准备展示精灵动画提示控件」返回 false），
  已移除。实机在 `purge=true` 下两次进入 PbActivity 均无崩溃、无异常堆栈；悬浮广告净化不依赖它。
  彩蛋已删除，该开关现在常驻显示在设置页「净化界面」分组，默认仍为关闭，
  净化功能本身尚未逐项验证效果。
- **native 库编译 arm64-v8a 与 x86_64**；32 位设备只有 DexKit 的预编译库，`libhide` / `libcheck` 不可用。
- 需 **LSPosed 2.1.0+**（API 102）。2.0.3 及更早版本提供的是 API 101，会因 `minApiVersion=102` 拒绝加载；
  KernelSU 3.x 还需单独安装 ZygiskNext，否则框架不会注入 zygote。
- 模块不再包含旧框架入口，降级安装旧版 LSPosed 会导致模块无法加载。
- 21 条 DexKit 规则与部分硬编码类名仍需在每个新版贴吧上重新校验；本仓库的校验基准是 22.13.1.1。

## 功能

- 自定义主页导航栏 / 禁用 Flutter
- 净化进吧、净化我的、隐藏小红点、默认隐藏帖子页右侧悬浮红包广告
- 过滤首页推荐、过滤帖子回复、过滤吧页面
- 进吧增加收藏、历史；我的收藏增加搜索、吧名；浏览历史增加搜索
- 搜索楼中楼增加查看主题贴；楼层增加点按效果；长按下载保存全部图片
- 备注关注的人、自动签到、自动切换夜间模式
- 吧页面起始页面改为最新、自动查看原图、使用媒体存储保存图片
- 禁用帖子手势、用赞踩差数代替赞数、禁止监听内部图片内容变化

## 构建

需要 JDK 17+、Android SDK 34、NDK 28、CMake。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/  （已带 v2 签名，可直接安装）
```

> 上游未配置 release 签名密钥（`app/gm.jks` 不在仓库中），所以 `assembleRelease` 产出的是**未签名** APK。
> [GitHub Releases](https://github.com/AIKA1024/TiebaTS/releases) 中提供的是用 Android debug keystore
> 签名后的 release 包，与 debug 变体同一证书，两者互为覆盖安装关系；该证书不保密，请勿用于生产分发。

版本号（`versionCode` / `versionName`）固定写在 `app/build.gradle.kts`，与发布 tag 一一对应；
从 tag 重新构建可得到相同版本、可覆盖安装的 APK，不再依赖本地 git 提交数或 CI 浅克隆的深度。

Windows 上若项目路径含非 ASCII 字符，AGP 8.5 会拒绝构建。**目录联接（junction）单独无效**，
因为 AGP 检查的是真实路径；必须追加实验性开关（仅命令行传入，不写进仓库配置）：

```bash
./gradlew assembleDebug -Pandroid.overridePathCheck=true
```

若在 ASCII 路径下 checkout 则无需该参数。CI 使用 `ubuntu-latest` + JDK 17，会显式安装
`platforms;android-34`、`build-tools;34.0.0` 与 `ndk;28.2.13676358`，并校验 APK 内的四个模块声明文件。

模块声明文件位于 `app/src/main/resources/META-INF/xposed/`。入口类 `XposedInit` 由 R8 规则保留原名，
同时配置了 `-adaptresourcefilecontents META-INF/xposed/java_init.list`，确保将来允许混淆时
`java_init.list` 仍与 dex 中的类名一致。

## 设置页署名

模块内设置页「关于」一栏同时标注本分支与上游，避免用户误以为仍在用原版：

| 条目 | 指向 |
|---|---|
| 本分支 / 维护者 / 源代码 / 问题反馈 / 适配信息 | AIKA1024/TiebaTS，弹窗展示分支来源、框架要求与模块版本 |
| 原作者 / 上游项目 / TG群 | 原作者 GM 与 GuhDoy/TiebaTS，保留上游署名并明确标注为上游 |
| 版本 | 本分支的 Releases（正式版）/ Actions（beta 版） |

使用协议（EULA）与异常提示也已注明本版本为非官方分支，反馈入口指向本仓库 Issues。

## 致谢与协议

- 本分支维护：[AIKA1024/TiebaTS](https://github.com/AIKA1024/TiebaTS)。
- 原项目：[GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS) by GM，感谢其完整的反混淆与 Hook 规则设计。
- 现代 API：[LSPosed/libxposed-api](https://github.com/JingMatrix/libxposed-api)。
- 复制或参考 **hook 规则** 用于开发百度贴吧模块需遵守 [GNU GPL v3](https://choosealicense.com/licenses/gpl-3.0/)；
  复制或参考其它代码用于开发其它模块或软件需遵守 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)。
  详见仓库 `LICENSE`。

本项目仅供学习与技术交流使用，不提供任何商业保证；使用模块所产生的账号风险与数据问题由使用者自行承担。
