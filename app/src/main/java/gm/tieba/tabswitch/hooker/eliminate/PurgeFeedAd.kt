package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.hooker.IHooker

/**
 * Hides in-feed advertising on Tieba 22.13.1.1+.
 *
 * Thread page (PbActivity), verified on a real device (OnePlus 7 Pro, 1440x3120):
 *   com.baidu.tieba.funad.view.FeedAdxNativePbView       - card below the first post
 *   com.baidu.tieba.feed.ad.compact.DelegateFunAdView    - wrapper for comment-area ads
 *   com.baidu.tieba.funad.view.FeedAdxNativeCommentView  - comment-area native ad
 *
 * Homepage "推荐" feed, verified on MuMu (1080x1920):
 *   com.baidu.tieba.feed.component.CardBannerView        - promotional banner inserted
 *        between thread cards (e.g. "参与2026TGA提名预测 瓜分千元京东卡"), with a close
 *        button. It lives inside a RecyclerView item whose root is
 *        com.baidu.tieba.feed.card.FeedCardView (226 px tall in that capture).
 *
 * All resource ids in this build are renamed to `id/obfuscated`, so hiding by id is not
 * an option; the class names themselves are not obfuscated.
 *
 * The thread-page cards are standalone children of the content container, so View.GONE
 * collapses them without a placeholder. The homepage banner is a RecyclerView item, so we
 * additionally collapse its enclosing FeedCardView — but only while that card still hosts
 * a banner, so a recycled card reused for a normal thread is never touched.
 *
 * Enabled by default, has its own switch, adds no DexKit rule and does not implement
 * Obfuscated.
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

        val feedCardClass = try {
            findClass(FEED_CARD_VIEW_CLASS)
        } catch (tr: Throwable) {
            null
        }

        fun hostsBanner(view: View): Boolean {
            if (classes.any { it.isInstance(view) }) return true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (hostsBanner(view.getChildAt(i))) return true
                }
            }
            return false
        }

        // A GONE RecyclerView item can still reserve its measured height. Collapse the
        // enclosing card too, but only cards that really carry an ad right now.
        fun collapseCard(banner: View) {
            if (feedCardClass == null) return
            var parent: ViewParent? = banner.parent
            while (parent != null) {
                if (parent is View && feedCardClass.isInstance(parent)) {
                    if (hostsBanner(parent)) {
                        parent.visibility = View.GONE
                    }
                    break
                }
                parent = parent.parent
            }
        }

        fun hideAdView(view: View) {
            view.visibility = View.GONE
            XposedBridge.log("TiebaTS: feed ad view hidden: ${view.javaClass.name}")
            // The parent is assigned only after inflation finishes, so defer the walk.
            view.post { runCatching { collapseCard(view) } }
        }

        // Hide as soon as the ad view is inflated so it never flashes on screen, and cover
        // every constructor because the SDK inflates different layouts for different ad types.
        classes.forEach { clazz ->
            clazz.declaredConstructors.forEach { ctor ->
                runCatching {
                    hookAfterConstructor(clazz, *ctor.parameterTypes) { param ->
                        (param.thisObject as? View)?.let { hideAdView(it) }
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
            val view = param.thisObject
            if (classes.any { it.isInstance(view) }) {
                param.args[0] = View.GONE
            } else if (feedCardClass?.isInstance(view) == true &&
                view is View && hostsBanner(view)
            ) {
                param.args[0] = View.GONE
            }
        }
    }

    companion object {
        const val KEY = "purge_feed_ad"
        private const val FEED_CARD_VIEW_CLASS = "com.baidu.tieba.feed.card.FeedCardView"
        private val AD_VIEW_CLASSES = listOf(
            "com.baidu.tieba.funad.view.FeedAdxNativePbView",
            "com.baidu.tieba.feed.ad.compact.DelegateFunAdView",
            "com.baidu.tieba.funad.view.FeedAdxNativeCommentView",
            // Promotional banner inserted between cards in the homepage "推荐" feed
            // (e.g. "参与2026TGA提名预测 瓜分千元京东卡"), with its own close button.
            "com.baidu.tieba.feed.component.CardBannerView"
        )
    }
}
