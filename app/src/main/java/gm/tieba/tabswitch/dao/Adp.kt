package gm.tieba.tabswitch.dao

import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.compat.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.putLikeForum

object Adp : XposedContext() {
    var BDUSS: String? = null
    var tbs: String? = null
    var account: String? = null

    fun initializeAdp() {
        refreshAccountData()
        refreshCache()
    }

    private fun refreshAccountData() {
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getBDUSS") { param ->
            BDUSS = param.result as String
        }
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getTbs") { param ->
            tbs = param.result as String
        }
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getAccount") { param ->
            account = param.result as String
        }
    }

    private fun refreshCache() {
        // Only 22.13.1.1+ is supported: DataRes$Builder (the pre-22.x holder) is gone.
        // Locate whichever current Builder still owns `like_forum` instead of assuming
        // a fixed class name / build() signature.
        val builder = listOf(
            "tbclient.ForumRecommend.NewRecommend\$Builder",
            "tbclient.ForumRecommend.LikeForum\$Builder"
        ).firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name)?.takeIf { clazz ->
                clazz.declaredFields.any { it.name == "like_forum" }
            }
        }
        val build = builder?.declaredMethods?.firstOrNull { it.name == "build" }
        if (build == null) {
            XposedBridge.log("TiebaTS: no ForumRecommend builder with like_forum, skipping follow cache")
            return
        }
        hookBeforeMethod(build) { param ->
            val forums: MutableSet<String?> = HashSet()
            val likeForumList = XposedHelpers.getObjectField(param.thisObject, "like_forum") as? List<*>
            likeForumList?.forEach { forums.add(XposedHelpers.getObjectField(it, "forum_name") as String) }
            putLikeForum(forums)
        }
    }
}
