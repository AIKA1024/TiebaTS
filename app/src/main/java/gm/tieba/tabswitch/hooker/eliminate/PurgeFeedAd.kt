package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.hooker.IHooker

/**
 * Hides the in-feed native ad cards ("梦幻西游：再续前缘" style) that Tieba 22.13.1.1+
 * inserts below the first post of a thread and between comments.
 *
 * Verified in the live view hierarchy of PbActivity on a real device (OnePlus 7 Pro,
 * 1440x3120, 22.13.1.1):
 *   com.baidu.tieba.funad.view.FeedAdxNativePbView        - card below the thread's first post
 *   com.baidu.tieba.feed.ad.compact.DelegateFunAdView     - wrapper for comment-area ads
 *   com.baidu.tieba.funad.view.FeedAdxNativeCommentView   - comment-area native ad
 * All resource ids in this build are renamed to `id/obfuscated`, so hiding by id is not
 * an option; the class names themselves are not obfuscated.
 *
 * These cards are standalone children of the thread content container rather than
 * RecyclerView items, so View.GONE removes them without leaving an empty placeholder.
 * The hooker is enabled by default, has its own switch, and only touches these classes.
 * It does not implement Obfuscated and adds no DexKit rule.
 */
class PurgeFeedAd : XposedContext(), IHooker {

    override fun key(): String = KEY

    override fun hook() {
        val classes = AD_VIEW_CLASSES.mapNotNull { name ->
            try {
                findClass(name)
            } catch (tr: Throwable) {
                XposedBridge.log("TiebaTS: $name not found, skipping feed ad purge for it")
                null
            }
        }
        if (classes.isEmpty()) {
            XposedBridge.log("TiebaTS: no feed ad view class found, skipping feed ad purge")
            return
        }

        // Hide as soon as the ad view is inflated so it never flashes on screen, and cover
        // every constructor because the SDK inflates different layouts for different ad types.
        classes.forEach { clazz ->
            clazz.declaredConstructors.forEach { ctor ->
                runCatching {
                    hookAfterConstructor(clazz, *ctor.parameterTypes) { param ->
                        (param.thisObject as? View)?.visibility = View.GONE
                    }
                }.onFailure { tr ->
                    XposedBridge.log(
                        "TiebaTS: skip feed ad constructor ${clazz.name}#" +
                            "${ctor.parameterTypes.joinToString()}: ${tr.javaClass.simpleName}"
                    )
                }
            }
        }

        // The host may show the card again after an ad refresh or a skin change. One global
        // callback covers all classes above; the type checks are cheap and it is registered
        // only once per process (well below LSPosed's 64-callback limit).
        hookBeforeMethod(
            View::class.java, "setVisibility", Int::class.javaPrimitiveType
        ) { param ->
            if (classes.any { it.isInstance(param.thisObject) }) {
                param.args[0] = View.GONE
            }
        }
    }

    companion object {
        const val KEY = "purge_feed_ad"
        private val AD_VIEW_CLASSES = listOf(
            "com.baidu.tieba.funad.view.FeedAdxNativePbView",
            "com.baidu.tieba.feed.ad.compact.DelegateFunAdView",
            "com.baidu.tieba.funad.view.FeedAdxNativeCommentView"
        )
    }
}
