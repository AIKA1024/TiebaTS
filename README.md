# TiebaTS — modern Xposed API 102 fork

提供修改百度贴吧底栏等个性化功能的 Xposed 模块。An Xposed module for Baidu Tieba.

> 本仓库是 [GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS) 的 fork。上游已于 **2024-08** 停止更新，
> 最后适配的客户端是贴吧 **12.66.1.0**；本分支继续维护，迁移到现代 Xposed API 并适配贴吧 22.x。

[![Android CI](https://github.com/AIKA1024/TiebaTS/actions/workflows/android.yml/badge.svg)](https://github.com/AIKA1024/TiebaTS/actions)

## 与上游的区别

| | 上游 3.0.3-beta (2024-08) | 本分支 |
|---|---|---|
| Xposed API | 旧 `de.robv.android.xposed` API 82 | 现代 `io.github.libxposed.api` **API 102** |
| 入口声明 | `assets/xposed_init` + Manifest meta-data | `META-INF/xposed/{module.prop, java_init.list, scope.list, native_init.list}` |
| Hook 模型 | `XC_MethodHook` before/after | 拦截器链 `hook(Executable).intercept(...)`，由兼容垫片还原旧语义 |
| 反射工具 | 框架提供的 `XposedHelpers` | 模块内自带实现（API 102 禁止访问旧包） |
| 模块资源 | `XModuleResources` 隐藏 API | 直接从模块 APK 读取 assets |
| 目标客户端 | 12.53.1.0 – 12.66.1.0 | **22.13.1.1**（APK 构建于 2026-10-27），下限 22.0.0.0 |

框架适配集中在 `XposedContext.kt` 和 `compat/` 目录，`hooker/` 下 31 个功能模块基本保持原样。

## 支持范围与要求

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

3 个已移除的类已做处理：

- `tbclient.ForumRecommend.DataRes$Builder` → 运行时在 `ForumRecommend.*$Builder` 中查找仍带 `like_forum` 字段的那个。
- `com.baidu.tieba.enterForum.recforum.message.RecommendForumRespondedMessage` → 类不存在时跳过，不再让整个净化功能失败。
- `com.baidu.tieba.pb.pb.sub.SubPbLayout` → 多候选探测 + 按 `createView`/`b` 方法定位 holder，找不到时优雅降级。

设置页代理 Activity 也改为运行时探测：22.13.1.1 中 `SecretSettingActivity` 已被删除，而
`setting.more.MoreActivity` 与 `person.more.PersonCenterMoreActivity` 仍存在（已用 APK 的 dex 类表逐个核对）。

### 22.x 运行时适配

符号还在不代表行为一致，另外做了几处针对新版的容错：

- **规则缺失不再让全部功能停摆**：上游只要有 1 条 DexKit 规则找不到就直接 `return`，31 个 Hooker 一个都不加载。
  现在改为记录日志并继续逐个安装，仅当过半规则缺失时才弹「规则异常」提示。
- **闪屏相关 hook 改为可选**：方法被移除时只跳过该 hook，不再中断整个 `Purge`。
- **`Switch` 去掉方法索引硬编码**：上游用 `mMethods[3]/[6]/[8]/[11]` 兜底，任何一次重编译都会调错方法；
  现在按 ON/OFF 枚举字段读状态，并遍历无参 void 方法寻找状态切换入口。
- **`NavigationBar` 支持嵌套查找**：新版部分 Activity 把导航栏放进 holder 对象，不再直接挂在 Activity 字段上。
- **native 痕迹探针**改为检测 `io/github/libxposed/api/XposedInterface`，不再检测早已不存在的旧 `XposedBridge`。

> 这只是**静态符号校验 + 源码级容错**，不等于真机逐项功能验证。24 条 DexKit 反混淆规则仍会在贴吧版本变化时
> 于首次启动自动重算；个别功能是否生效需要在设备上确认。

## 已知限制

- **楼层点按效果（Ripple）**：新版贴吧重构了楼中楼布局，即使探测到候选类也可能无法生效，需实机调整。
- **关注吧缓存**：依赖 protobuf 字段名 `like_forum`，若百度再次改协议需同步更新。
- **native 库仅编译 arm64-v8a**（`abiFilters`）：32 位设备上隐藏/痕迹检测相关 native 功能不可用。
- 模块不再包含旧框架入口，降级安装旧版 LSPosed 会导致模块无法加载。
- 24 条 DexKit 规则与部分硬编码类名仍需在每个新版贴吧上重新校验；本仓库的校验基准是 22.13.1.1。

## 功能

- 自定义主页导航栏 / 禁用 Flutter
- 净化进吧、净化我的、隐藏小红点
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
> 若要安装 release 变体，需自行用 `apksigner` 签名；本次交付另外提供了一个用 Android debug keystore
> 签名的 `TS_4.0.0-beta.11_release_signed.apk`。debug 与该文件使用同一证书，互为覆盖安装关系。

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

## 致谢与协议

- 原项目：[GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS) by GM，感谢其完整的反混淆与 Hook 规则设计。
- 现代 API：[LSPosed/libxposed-api](https://github.com/JingMatrix/libxposed-api)。
- 复制或参考 **hook 规则** 用于开发百度贴吧模块需遵守 [GNU GPL v3](https://choosealicense.com/licenses/gpl-3.0/)；
  复制或参考其它代码用于开发其它模块或软件需遵守 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)。
  详见仓库 `LICENSE`。

本项目仅供学习与技术交流使用，不提供任何商业保证；使用模块所产生的账号风险与数据问题由使用者自行承担。
