package gm.tieba.tabswitch.compat

import io.github.libxposed.api.XposedInterface

typealias HookParam = XC_MethodHook.MethodHookParam
typealias Unhook = XC_MethodHook.Unhook

/** Equivalent of the legacy `XC_MethodReplacement.returnConstant(...)`. */
class XC_MethodReplacement private constructor(private val constant: Any?) : XC_MethodHook() {
    override fun beforeHookedMethod(param: HookParam) {
        param.setResult(constant)
    }

    companion object {
        @JvmStatic
        fun returnConstant(result: Any?): XC_MethodReplacement = XC_MethodReplacement(result)
    }
}

/** Legacy `XCallback` priority constants mapped onto libxposed priorities. */
object XCallback {
    const val PRIORITY_HIGHEST: Int = XposedInterface.PRIORITY_HIGHEST
    const val PRIORITY_LOWEST: Int = XposedInterface.PRIORITY_LOWEST
    const val PRIORITY_DEFAULT: Int = XposedInterface.PRIORITY_DEFAULT
}
