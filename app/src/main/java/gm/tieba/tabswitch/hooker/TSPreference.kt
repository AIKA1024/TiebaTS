package gm.tieba.tabswitch.hooker

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.commit
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getIsAutoSignEnabled
import gm.tieba.tabswitch.dao.Preferences.getIsEULAAccepted
import gm.tieba.tabswitch.dao.Preferences.getIsPurgeEnabled
import gm.tieba.tabswitch.dao.Preferences.putAutoSignEnabled
import gm.tieba.tabswitch.dao.Preferences.putEULAAccepted
import gm.tieba.tabswitch.dao.Preferences.putPurgeEnabled
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.PreferenceLayout
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.SwitchButtonHolder
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.createButton
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.createTextView
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.randomToast
import gm.tieba.tabswitch.hooker.eliminate.PurgeFloatAd
import gm.tieba.tabswitch.hooker.extra.TraceChecker
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.restart
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.util.Locale

class TSPreference : XposedContext(), IHooker {

    override fun key(): String {
        return "ts_pref"
    }

    override fun hook() {
        hookBeforeMethod(
            Dialog::class.java,
            "dismissDialog"
        ) { param ->
            val dialog = param.thisObject as? Dialog
            dialog?.takeIf { it.isShowing }?.window?.currentFocus?.let { view ->
                val imm = view.context.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.rootView.windowToken, 0)
            }
        }

        // Only 22.13.1.1+ is supported. The legacy proxy Activity (SecretSettingActivity)
        // and its constructor-based DexKit rule are gone; MoreActivity renders the
        // preference page directly when launched with a proxyPage extra.
        hookAfterMethod(
            PROXY_ACTIVITY,
            "onCreate", Bundle::class.java
        ) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfterMethod
            try {
                val container = findPreferenceContainer(activity)
                if (container == null) {
                    XposedBridge.log("TSPreference: preference container not found in MoreActivity")
                    return@hookAfterMethod
                }
                val proxyPage = activity.intent?.getStringExtra("proxyPage")
                if (proxyPage == null) {
                    addEntryButton(container, activity)
                } else {
                    showProxyPage(container, activity, proxyPage)
                }
            } catch (tr: Throwable) {
                // A failed injection must never break the host Activity's onCreate.
                XposedBridge.log(tr)
            }
        }
    }

    private fun findPreferenceContainer(activity: Activity): LinearLayout? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return findScrollContent(content)
    }

    private fun findScrollContent(view: View): LinearLayout? {
        if (view is ScrollView) {
            val child = view.getChildAt(0)
            if (child is LinearLayout) return child
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findScrollContent(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun addEntryButton(container: LinearLayout, activity: Activity) {
        // Every tap starts a new MoreActivity instance; do not stack duplicate rows.
        if (container.findViewWithTag<View>(ENTRY_TAG) != null) return
        val button = createButton(MAIN, null, true) { startRootPreferenceActivity(activity) }
        button.tag = ENTRY_TAG
        container.addView(button, ENTRY_INDEX.coerceAtMost(container.childCount))
    }

    private fun showProxyPage(container: LinearLayout, activity: Activity, proxyPage: String) {
        val preferenceLayout = when (proxyPage) {
            MAIN -> createRootPreference(activity)
            MODIFY_TAB -> createModifyTabPreference(activity)
            TRACE -> createHidePreference(activity)
            else -> null
        } ?: return
        // MoreActivity has no NavigationBar holder; put "重启" at the top of the list.
        preferenceLayout.addView(
            createButton("重启", null, true) { _ ->
                commit()
                restart(activity)
            }, 0
        )
        container.removeAllViews()
        container.addView(preferenceLayout)
    }

    private fun startRootPreferenceActivity(activity: Activity) {
        if (!getIsEULAAccepted()) {
            val stringBuilder = StringBuilder().apply {
                append(strings["EULA"])
                if (isModuleBetaVersion) {
                    append("\n\n").append(strings["dev_tip"])
                }
            }

            val alert = AlertDialog.Builder(
                activity,
                getDialogTheme(getContext())
            )
                .setTitle("使用协议")
                .setMessage(stringBuilder.toString())
                .setNegativeButton(activity.getString(android.R.string.cancel), null)
                .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                    putEULAAccepted()
                    startRootPreferenceActivity(activity)
                }
                .create()

            alert.show()
            fixAlertDialogWidth(alert)

        } else {
            activity.startActivity(Intent().apply {
                setClassName(activity, PROXY_ACTIVITY)
                putExtra("proxyPage", MAIN)
            })
        }
    }

    private fun openUri(activity: Activity, uri: String?) {
        val target = uri ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        } catch (tr: Throwable) {
            // Some builds restrict external intents; never let the settings page crash.
            XposedBridge.log(tr)
        }
    }

    private fun showInfoDialog(activity: Activity, title: String, message: String, uri: String?) {
        val builder = AlertDialog.Builder(activity, getDialogTheme(getContext()))
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(activity.getString(android.R.string.ok), null)
        if (uri != null) {
            builder.setNeutralButton("打开仓库") { _, _ -> openUri(activity, uri) }
        }
        val alert = builder.create()
        alert.show()
        fixAlertDialogWidth(alert)
    }

    private fun createRootPreference(activity: Activity): LinearLayout {
        val isPurgeEnabled = getIsPurgeEnabled()
        val preferenceLayout = PreferenceLayout(activity)

        preferenceLayout.addView(createTextView(if (isPurgeEnabled) "轻车简从" else "净化界面"))
        preferenceLayout.addView(createButton(MODIFY_TAB, null, true) { _ ->
            activity.startActivity(Intent().apply {
                setClassName(activity, PROXY_ACTIVITY)
                putExtra("proxyPage", MODIFY_TAB)
            })
        })

        if (isPurgeEnabled) {
            preferenceLayout.addView(SwitchButtonHolder(activity, "真正的净化界面", "purge", SwitchButtonHolder.TYPE_SWITCH))
        }

        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏右侧悬浮广告", PurgeFloatAd.KEY, SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "净化进吧", "purge_enter", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "净化我的", "purge_my", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "默认折叠置顶帖", "fold_top_card_view", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "只推荐已关注的吧", "follow_filter", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页视频贴", "purge_video", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤首页推荐", "personalized_filter", SwitchButtonHolder.TYPE_DIALOG))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤吧页面", "frs_page_filter", SwitchButtonHolder.TYPE_DIALOG))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤帖子回复", "content_filter", SwitchButtonHolder.TYPE_DIALOG))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤用户", "user_filter", SwitchButtonHolder.TYPE_DIALOG))

        preferenceLayout.addView(createTextView(if (isPurgeEnabled) "别出新意" else "增加功能"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "浏览历史增加搜索", "history_cache", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "楼层增加点按效果", "ripple", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "长按下载保存全部图片", "save_images", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "弹窗自由复制", "select_clipboard", SwitchButtonHolder.TYPE_SWITCH))

        preferenceLayout.addView(createTextView(if (isPurgeEnabled) "垂手可得" else "自动化"))

        val autoSign = SwitchButtonHolder(activity, "自动签到", "auto_sign", SwitchButtonHolder.TYPE_SWITCH)
        autoSign.setOnButtonClickListener { _ ->
            if (!getIsAutoSignEnabled()) {
                val alert = AlertDialog.Builder(
                    activity,
                    getDialogTheme(getContext())
                )
                    .setTitle("提示")
                    .setMessage("这是一个需要网络请求并且有封号风险的功能，您需要自行承担使用此功能的风险，请谨慎使用！")
                    .setNegativeButton(activity.getString(android.R.string.cancel), null)
                    .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                        putAutoSignEnabled()
                        autoSign.bdSwitch.turnOn()
                    }
                    .create()
                alert.show()
                fixAlertDialogWidth(alert)

            } else {
                autoSign.bdSwitch.changeState()
            }
        }
        preferenceLayout.addView(autoSign)

        preferenceLayout.addView(SwitchButtonHolder(activity, "自动打开一键签到", "open_sign", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "吧页面起始页面改为最新", "frs_tab", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "消息页面起始页面改为通知", "msg_center_tab", SwitchButtonHolder.TYPE_SWITCH))

        val originSrcOnlyWifiButton = SwitchButtonHolder(activity, "自动查看原图仅WiFi下生效", "origin_src_only_wifi", SwitchButtonHolder.TYPE_SWITCH)
        val originSrcButton = SwitchButtonHolder(activity, "自动查看原图", "origin_src", SwitchButtonHolder.TYPE_SWITCH)
        originSrcButton.setOnButtonClickListener { _ ->
            originSrcButton.bdSwitch.changeState()
            originSrcOnlyWifiButton.switchButton.visibility = if (getBoolean("origin_src")) View.VISIBLE else View.GONE
        }
        originSrcOnlyWifiButton.switchButton.visibility = if (getBoolean("origin_src")) View.VISIBLE else View.GONE

        preferenceLayout.addView(originSrcButton)
        preferenceLayout.addView(originSrcOnlyWifiButton)

        preferenceLayout.addView(createTextView(if (isPurgeEnabled) "奇怪怪" else "其它"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏小红点", "red_tip", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁用更新提示", "remove_update", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁用帖子手势", "forbid_gesture", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "用赞踩差数代替赞数", "agree_num", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁止检测通知开启状态", "notification_detect", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁止首页自动刷新", "auto_refresh", SwitchButtonHolder.TYPE_SWITCH))

        if (isModuleBetaVersion) {
            preferenceLayout.addView(SwitchButtonHolder(activity, "重定向内部日志", "log_redirect", SwitchButtonHolder.TYPE_SWITCH))
        }

        preferenceLayout.addView(createButton(TRACE, "希望有一天不再需要贴吧TS", true) { _ ->
            activity.startActivity(Intent().apply {
                setClassName(activity, PROXY_ACTIVITY)
                putExtra("proxyPage", TRACE)
            })
        })

        preferenceLayout.addView(createTextView(if (isPurgeEnabled) "关于就是关于" else "关于"))

        // Upstream only credited GM here. Keep the original credit, but make it clear which
        // fork is actually running and where users of this fork should go for help.
        preferenceLayout.addView(createButton("本分支", "AIKA1024 · 非官方维护", true) { _ ->
            showInfoDialog(
                activity,
                "关于本分支",
                """
                贴吧TS · AIKA1024 维护分支

                原作者：GM（GuhDoy/TiebaTS，上游已于 2024-08 停止更新）
                本分支：AIKA1024/TiebaTS，迁移到现代 Xposed API 102 并适配贴吧 22.x。

                模块版本：%s（versionCode %d）
                源码：github.com/AIKA1024/TiebaTS
                反馈：github.com/AIKA1024/TiebaTS/issues

                本分支为非官方维护版本，与上游作者无关；
                上游的反混淆与 Hook 规则设计归原作者所有。
                """.trimIndent().format(Locale.CHINA, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                strings["fork_uri"]
            )
        })

        preferenceLayout.addView(createButton("维护者", "AIKA1024", true) { _ ->
            openUri(activity, strings["fork_author_uri"])
        })

        preferenceLayout.addView(createButton("原作者", "GM · 上游项目", true) { _ ->
            sCount++
            if (sCount % 3 == 0) {
                showTbToast(randomToast(), TbToast.LENGTH_SHORT)
            }
            if (!isPurgeEnabled && sCount >= 10) {
                putPurgeEnabled()
                activity.recreate()
            }
        })

        preferenceLayout.addView(createButton("源代码", "AIKA1024/TiebaTS", true) { _ ->
            openUri(activity, strings["fork_uri"])
        })

        preferenceLayout.addView(createButton("上游项目", "GuhDoy/TiebaTS", true) { _ ->
            openUri(activity, strings["upstream_uri"])
        })

        preferenceLayout.addView(createButton("TG群", "上游原项目交流群", true) { _ ->
            openUri(activity, strings["upstream_tg_uri"])
        })

        preferenceLayout.addView(createButton("适配信息", "API 102 · LSPosed 2.1+", true) { _ ->
            showInfoDialog(
                activity,
                "适配信息",
                """
                框架：LSPosed 2.1.0+ 或其它实现 libxposed API 102 的框架。
                KernelSU 3.x 需另装 ZygiskNext；LSPosed 1.x、EdXposed、原版 Xposed 不兼容。

                目标客户端：百度贴吧 %s 及以上版本，更早版本不受支持。
                作用域：com.baidu.tieba。

                21 条 DexKit 规则中有 5 条在 22.13.1.1 上失效，模块会降级运行；
                部分功能效果仍需实机逐项验证，详见仓库 README。
                """.trimIndent().format(Locale.CHINA, BuildConfig.TARGET_VERSION),
                strings["fork_uri"]
            )
        })

        preferenceLayout.addView(createButton("问题反馈", "GitHub Issues", true) { _ ->
            openUri(activity, strings["fork_issues_uri"])
        })

        preferenceLayout.addView(
            createButton("版本", "%s（API 102 fork）".format(Locale.CHINA, BuildConfig.VERSION_NAME), true) { _ ->
                openUri(activity, if (isModuleBetaVersion) strings["ci_uri"] else strings["release_uri"])
            })
        return preferenceLayout
    }

    private fun createModifyTabPreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)
        preferenceLayout.addView(createTextView("主页导航栏"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏首页", "home_recommend", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏进吧", "enter_forum", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏发帖", "write_thread", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏消息", "im_message", SwitchButtonHolder.TYPE_SWITCH))

        preferenceLayout.addView(createTextView("其他"))
        val transitionAnimation = SwitchButtonHolder(activity, "修复过渡动画", "transition_animation", SwitchButtonHolder.TYPE_SWITCH)

        val shouldEnableTransitionAnimationFix = Build.VERSION.SDK_INT >= 34
        transitionAnimation.setOnButtonClickListener { _ ->
            if (!shouldEnableTransitionAnimationFix) {
                showTbToast("当前 Android 版本不支持此功能", TbToast.LENGTH_SHORT)
            } else {
                transitionAnimation.bdSwitch.changeState()
            }
        }
        preferenceLayout.addView(transitionAnimation)

        return preferenceLayout
    }

    private fun createHidePreference(activity: Activity): LinearLayout {
        val isPurgeEnabled = getIsPurgeEnabled()
        val preferenceLayout = PreferenceLayout(activity)
        if (isPurgeEnabled || BuildConfig.DEBUG) {
            preferenceLayout.addView(createTextView("隐藏设置"))
            preferenceLayout.addView(SwitchButtonHolder(activity, if (isPurgeEnabled) "藏起尾巴" else "隐藏模块", "hide", SwitchButtonHolder.TYPE_SWITCH))
            preferenceLayout.addView(SwitchButtonHolder(activity, if (isPurgeEnabled) "藏起尾巴（原生）" else "隐藏模块（原生）", "hide_native", SwitchButtonHolder.TYPE_SWITCH))
        }

        preferenceLayout.addView(createTextView("检测设置"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测 Xposed", "check_xposed", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测模块", "check_module", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测堆栈（重启才能真正生效）", "check_stack_trace", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(
            createButton(
                if (isPurgeEnabled) "捏捏尾巴" else "检测模块",
                Process.myPid().toString(),
                true
            ) { _ -> TraceChecker(preferenceLayout).checkAll() })
        TraceChecker.sChildCount = preferenceLayout.childCount
        return preferenceLayout
    }

    companion object {
        const val MAIN: String = "贴吧TS设置"
        const val MODIFY_TAB: String = "修改页面"
        const val TRACE: String = "痕迹"
        // Verified in the 22.13.1.1 dex: SecretSettingActivity is gone. MoreActivity is the
        // only preference host for supported clients, so no runtime class probing is needed.
        private const val PROXY_ACTIVITY = "com.baidu.tieba.setting.more.MoreActivity"
        private const val ENTRY_TAG = "gm.tieba.tabswitch.ts_pref_entry"
        private const val ENTRY_INDEX = 11
        private var sCount = 0
    }
}
