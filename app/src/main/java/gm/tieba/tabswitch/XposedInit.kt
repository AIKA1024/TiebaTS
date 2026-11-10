package gm.tieba.tabswitch

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.XposedContext.Companion.attachBaseContext
import gm.tieba.tabswitch.XposedContext.Companion.exceptions
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.framework
import gm.tieba.tabswitch.XposedContext.Companion.getContext
import gm.tieba.tabswitch.XposedContext.Companion.hookAfterMethod
import gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod
import gm.tieba.tabswitch.XposedContext.Companion.isModuleBetaVersion
import gm.tieba.tabswitch.XposedContext.Companion.sClassLoader
import gm.tieba.tabswitch.XposedContext.Companion.sPath
import gm.tieba.tabswitch.XposedContext.Companion.sProcessName
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.compat.XposedHelpers
import gm.tieba.tabswitch.dao.AcRules
import gm.tieba.tabswitch.dao.AcRules.isRuleFound
import gm.tieba.tabswitch.dao.Adp.initializeAdp
import gm.tieba.tabswitch.dao.Preferences
import gm.tieba.tabswitch.dao.Preferences.getAll
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.dao.Preferences.putSignature
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.TSPreference
import gm.tieba.tabswitch.hooker.add.HistoryCache
import gm.tieba.tabswitch.hooker.add.Ripple
import gm.tieba.tabswitch.hooker.add.SaveImages
import gm.tieba.tabswitch.hooker.add.SelectClipboard
import gm.tieba.tabswitch.hooker.auto.AgreeNum
import gm.tieba.tabswitch.hooker.auto.AutoSign
import gm.tieba.tabswitch.hooker.auto.FrsTab
import gm.tieba.tabswitch.hooker.auto.MsgCenterTab
import gm.tieba.tabswitch.hooker.auto.NotificationDetect
import gm.tieba.tabswitch.hooker.auto.OpenSign
import gm.tieba.tabswitch.hooker.auto.OriginSrc
import gm.tieba.tabswitch.hooker.auto.TransitionAnimation
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getTbVersion
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isSupportedTbVersion
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isVersionChanged
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.saveAndRestart
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHooker
import gm.tieba.tabswitch.hooker.eliminate.ContentFilter
import gm.tieba.tabswitch.hooker.eliminate.FoldTopCardView
import gm.tieba.tabswitch.hooker.eliminate.FollowFilter
import gm.tieba.tabswitch.hooker.eliminate.FragmentTab
import gm.tieba.tabswitch.hooker.eliminate.FrsPageFilter
import gm.tieba.tabswitch.hooker.eliminate.PersonalizedFilter
import gm.tieba.tabswitch.hooker.eliminate.Purge
import gm.tieba.tabswitch.hooker.eliminate.PurgeEnter
import gm.tieba.tabswitch.hooker.eliminate.PurgeFloatAd
import gm.tieba.tabswitch.hooker.eliminate.PurgeMy
import gm.tieba.tabswitch.hooker.eliminate.PurgeVideo
import gm.tieba.tabswitch.hooker.eliminate.RedTip
import gm.tieba.tabswitch.hooker.eliminate.RemoveUpdate
import gm.tieba.tabswitch.hooker.eliminate.UserFilter
import gm.tieba.tabswitch.hooker.extra.AutoRefresh
import gm.tieba.tabswitch.hooker.extra.ForbidGesture
import gm.tieba.tabswitch.hooker.extra.Hide
import gm.tieba.tabswitch.hooker.extra.LogRedirect
import gm.tieba.tabswitch.hooker.extra.StackTrace
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.TbToast
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Locale

/**
 * Module entry for the modern libxposed API 102.
 *
 * The framework instantiates this class, attaches itself, then calls [onModuleLoaded] followed by
 * [onPackageReady]. There is no zygote injection and no `assets/xposed_init`; the declaration lives
 * in `META-INF/xposed/java_init.list`.
 */
class XposedInit : XposedModule() {

    /**
     * Idempotent: [onModuleLoaded] normally runs first, but a framework may deliver the package
     * lifecycle callbacks in a different order. Both entry points must leave the context usable.
     */
    private fun attachFramework() {
        framework = this
        sPath = getModuleApplicationInfo().sourceDir
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        attachFramework()
        sProcessName = param.processName
        XposedBridge.log("Loading TiebaTS, version ${BuildConfig.VERSION_CODE}")
        XposedBridge.log(
            "Framework ${getFrameworkName()} ${getFrameworkVersion()} " +
                "(${getFrameworkVersionCode()}), Xposed API ${getApiVersion()}"
        )
    }

    override fun onPackageReady(packageParam: PackageReadyParam) {
        if (packageParam.packageName != "com.baidu.tieba" || !packageParam.isFirstPackage) return
        attachFramework()

        sClassLoader = packageParam.classLoader
        val appComponentFactory = packageParam.appComponentFactory

        // Certain Flutter pages do not load in LSPatch unless the plugin is loaded manually.
        hookAfterMethod(
            "com.baidu.tieba.flutter.FlutterPluginManager",
            "invokePlugin", "com.baidu.nps.main.invoke.IInvokeCallback"
        ) { param ->
            try {
                val npsManager = XposedHelpers.callStaticMethod(
                    findClass("com.baidu.nps.main.manager.NPSManager"),
                    "getInstance"
                )
                XposedHelpers.callMethod(
                    npsManager,
                    "loadClazz",
                    "com.baidu.tieba.plugin.flutter",
                    "com.baidu.tieba.flutter.FlutterPluginImpl",
                    findClass("com.baidu.tieba.flutter.IFlutterPlugin"),
                    param.args[0]
                )
            } catch (ignored: Error) {
            }
        }

        hookAfterMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate", Application::class.java
        ) { param ->
            if (param.args[0] !is Application) return@hookAfterMethod

            attachBaseContext((param.args[0] as Application))
            Preferences.init(getContext())
            // New in 4.0.0-beta.19: hide the right-side floating ad out of the box, while
            // keeping it switchable in the preference page. Writing the default once also keeps
            // the switch UI in sync with the actual hook state.
            if (!getAll().containsKey(PurgeFloatAd.KEY)) {
                putBoolean(PurgeFloatAd.KEY, true)
            }
            AcRules.init(getContext())
            DeobfuscationHelper.sCurrentTbVersion = getTbVersion(getContext())
            XposedBridge.log("TB client detected, version ${DeobfuscationHelper.sCurrentTbVersion}")

            // LSPatch sets appComponentFactory to null, which breaks FlutterPageActivity.
            if (getContext().applicationInfo.appComponentFactory == null) {
                XposedBridge.log("Applying LSPatch workaround")
                hookAfterMethod(
                    Instrumentation::class.java,
                    "getFactory", String::class.java
                ) { hookParam ->
                    if (hookParam.args[0].toString() == "com.baidu.tieba") {
                        hookParam.result = appComponentFactory
                    }
                }
            }

            val hookers: List<IHooker> = listOf(
                TSPreference(),
                FragmentTab(),
                PurgeFloatAd(),
                Purge(),
                PurgeEnter(),
                PurgeMy(),
                RedTip(),
                FollowFilter(),
                PersonalizedFilter(),
                ContentFilter(),
                FrsPageFilter(),
                HistoryCache(),
                Ripple(),
                SaveImages(),
                AutoSign(),
                OpenSign(),
                OriginSrc(),
                ForbidGesture(),
                AgreeNum(),
                FrsTab(),
                Hide(),
                StackTrace(),
                RemoveUpdate(),
                FoldTopCardView(),
                MsgCenterTab(),
                NotificationDetect(),
                PurgeVideo(),
                SelectClipboard(),
                UserFilter(),
                TransitionAnimation(),
                AutoRefresh(),
                LogRedirect()
            )
            val matchers = ArrayList<Obfuscated>(hookers.size + 1)
            matchers.add(TbToast())
            hookers.forEach { hooker ->
                if (hooker is Obfuscated) {
                    matchers.add(hooker as Obfuscated)
                }
            }

            val matchersList = matchers.flatMap { it.matchers() }

            // All rules target 22.13.1.1+; upstream's per-rule version gates are gone.

            if (isVersionChanged(getContext())) {
                if ("com.baidu.tieba" == sProcessName) {
                    hookAfterMethod(
                        "com.baidu.tieba.tblauncher.MainTabActivity",
                        "onCreate", Bundle::class.java
                    ) { hookParam ->
                        val activity = hookParam.thisObject as Activity
                        activity.startActivity(Intent(
                            activity,
                            findClass("com.baidu.tieba.LogoActivity")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) })
                    }
                }
                XposedBridge.log("Tb version changed, performing deobfuscation")

                DeobfuscationHooker(matchersList).hook()
                return@hookAfterMethod
            }

            val lostList = matchersList.map { it.toString() }.filter { !isRuleFound(it) }

            // On Tieba 22.x a handful of DexKit rules legitimately disappear (removed protobuf
            // holders, renamed activities). Upstream aborted here and loaded ZERO hookers, which
            // disabled every feature. Keep the warning but let each Hooker install independently;
            // hookers.forEach below already isolates failures per hooker.
            if (lostList.isNotEmpty()) {
                XposedBridge.log(
                    "TiebaTS: %d of %d deobfuscation rule(s) unavailable, continuing with reduced coverage: %s"
                        .format(Locale.CHINA, lostList.size, matchersList.size, lostList)
                )
                exceptions["lost_rules"] = IllegalStateException(lostList.joinToString(", "))
            }

            // Only interrupt the user when most rules are gone (e.g. a wholly unsupported build).
            if (lostList.size >= matchersList.size / 2 && matchersList.isNotEmpty()) {
                hookBeforeMethod(
                    "com.baidu.tieba.tblauncher.MainTabActivity",
                    "onCreate", Bundle::class.java
                ) { hookParam ->
                    val activity = hookParam.thisObject as Activity
                    val messages = ArrayList<String?>().apply {
                        add(
                            if (isSupportedTbVersion(BuildConfig.MIN_VERSION)) {
                                strings["exception_rules_incomplete"]
                            } else {
                                strings["version_mismatch"]
                            }
                        )
                        add("贴吧版本：%s, 模块版本：%d".format(Locale.CHINA, DeobfuscationHelper.sCurrentTbVersion, BuildConfig.VERSION_CODE))
                        add("%d rule(s) lost: %s".format(Locale.CHINA, lostList.size, lostList))
                        add("模块仍会继续加载，缺失规则对应的功能可能不可用。")
                    }
                    val message = TextUtils.join("\n", messages)
                    XposedBridge.log(message)

                    val alert = AlertDialog.Builder(
                        activity,
                        getDialogTheme(getContext())
                    )
                        .setTitle("规则异常")
                        .setMessage(message)
                        .setCancelable(false)
                        .setNeutralButton("更新模块") { _, _ ->
                            activity.startActivity(Intent().apply {
                                setAction("android.intent.action.VIEW")
                                if (isModuleBetaVersion) {
                                    setData(Uri.parse(strings["ci_uri"]))
                                } else {
                                    setData(Uri.parse(strings["release_uri"]))
                                }
                            })
                        }
                        .setNegativeButton(activity.getString(android.R.string.cancel), null)
                        .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                            putSignature(0)
                            saveAndRestart(activity, "unknown", null)
                        }.create()

                    alert.show()
                    fixAlertDialogWidth(alert)
                }
                // No early return: install the remaining hookers regardless.
            }

            initializeAdp()
            // META-INF/xposed/native_init.list makes the framework dlopen libhide.so and call its
            // native_init() to supply NativeAPIEntries.hook_func. loadLibrary() then triggers
            // JNI_OnLoad, which installs the FindClass disguise. Keep the upstream order: the
            // library must NOT be loaded through System.load() here or hook_func would be null.
            if (getBoolean("hide_native")) {
                try {
                    System.loadLibrary("hide")
                } catch (e: UnsatisfiedLinkError) {
                    XposedBridge.log(e)
                }
            }

            val shouldEnableTransitionAnimationFix = Build.VERSION.SDK_INT >= 34
            if (!shouldEnableTransitionAnimationFix && getBoolean("transition_animation")) {
                putBoolean("transition_animation", false)
            }

            if (!isModuleBetaVersion && getBoolean("log_redirect")) {
                putBoolean("log_redirect", false)
            }

            val activeHookerKeys = getAll().entries
                .filter { it.value != false }
                .map { it.key }
                .toMutableSet()
                .apply {
                    add("ts_pref")
                    add("fragment_tab")
                }
            XposedBridge.log("Enabled hooks: ${activeHookerKeys.joinToString(", ")}")

            hookers.forEach { hooker ->
                try {
                    if (activeHookerKeys.contains(hooker.key())) {
                        hooker.hook()
                    }
                } catch (tr: Throwable) {
                    XposedBridge.log(tr)
                    exceptions[hooker.key()] = tr
                }
            }
        }
    }
}
