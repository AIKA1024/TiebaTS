package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.hooker.IHooker

/**
 * Hides the right-side floating ad ("50元 最高红包", live/activity cards) shown over the
 * thread detail page and the home feed on Tieba 22.13.1.1+.
 *
 * The view is `com.baidu.tbadk.widget.RightFloatLayerView`, verified in the target APK's dex
 * class table and in the live view hierarchy (264x343 dp at the right edge of PbActivity).
 * Its resource ids are all renamed to `id/obfuscated`, so hiding by id is not an option.
 *
 * Upstream already tried to suppress it from inside [Purge] by forcing the class's no-arg
 * boolean methods to false, but that hooker sits behind the hidden "真正的净化界面" switch
 * (default off, unlocked by tapping the author ten times). This hooker is enabled by default
 * and only touches this one view class, so it cannot affect anything else in the client.
 */
class PurgeFloatAd : XposedContext(), IHooker {

    override fun key(): String = KEY

    override fun hook() {
        val clazz = try {
            findClass(FLOAT_LAYER_VIEW)
        } catch (tr: Throwable) {
            XposedBridge.log("TiebaTS: $FLOAT_LAYER_VIEW not found, skipping float ad purge")
            return
        }

        // The ad SDK inflates the view asynchronously. Hide it as soon as it is constructed so
        // it never flashes on screen, even if the host never calls setVisibility itself.
        clazz.declaredConstructors.forEach { ctor ->
            runCatching {
                hookAfterConstructor(clazz, *ctor.parameterTypes) { param ->
                    (param.thisObject as? View)?.visibility = View.GONE
                }
            }.onFailure { tr ->
                XposedBridge.log("TiebaTS: skip float ad constructor ${ctor.parameterTypes.joinToString()}: ${tr.javaClass.simpleName}")
            }
        }

        // The host may show it again after an ad refresh / skin change. One global hook is
        // enough: the type check is cheap and LSPosed registers this callback only once.
        hookBeforeMethod(
            View::class.java, "setVisibility", Int::class.javaPrimitiveType
        ) { param ->
            if (clazz.isInstance(param.thisObject)) {
                param.args[0] = View.GONE
            }
        }
    }

    companion object {
        const val KEY = "purge_float_ad"
        private const val FLOAT_LAYER_VIEW = "com.baidu.tbadk.widget.RightFloatLayerView"
    }
}
